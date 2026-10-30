/*
 * XInput bridge for the Termux:X11 onscreen gamepad.
 *
 * Replaces xinput1_3.dll / xinput1_4.dll / xinput9_1_0.dll inside Wine. Controller 0 is fed by
 * the Termux:X11 app over UDP on 127.0.0.1 (port 7949, or XINPUT_BRIDGE_PORT):
 *   dll -> app  "XGPH"                                   hello, repeated every second
 *   dll -> app  "XGPV" u16 left_motor u16 right_motor    rumble
 *   app -> dll  "XGP1" u16 buttons u8 lt u8 rt i16 lx ly rx ry   state (little endian, 16 bytes)
 * The app resends the state at least every 100 ms; without it for 3 s the pad is "disconnected".
 */
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdlib.h>
#include <string.h>

#define BRIDGE_DEFAULT_PORT 7949
#define TIMEOUT_MS 3000
#define GUIDE_BUTTON 0x0400

typedef struct {
    WORD wButtons;
    BYTE bLeftTrigger;
    BYTE bRightTrigger;
    SHORT sThumbLX, sThumbLY, sThumbRX, sThumbRY;
} GAMEPAD;

typedef struct { DWORD dwPacketNumber; GAMEPAD Gamepad; } STATE;
typedef struct { WORD wLeftMotorSpeed; WORD wRightMotorSpeed; } VIBRATION;
typedef struct { BYTE Type; BYTE SubType; WORD Flags; GAMEPAD Gamepad; VIBRATION Vibration; } CAPABILITIES;
typedef struct { BYTE BatteryType; BYTE BatteryLevel; } BATTERY_INFORMATION;
typedef struct { WORD VirtualKey; WCHAR Unicode; WORD Flags; BYTE UserIndex; BYTE HidCode; } KEYSTROKE;

static CRITICAL_SECTION lock;
static GAMEPAD pad;
static DWORD packetNumber, lastPacketTick;
static BOOL enabled = TRUE;
static volatile LONG started;
static HANDLE firstPacket;
static SOCKET sock = INVALID_SOCKET;
static struct sockaddr_in appAddr;

static BOOL connected(void) {
    return lastPacketTick != 0 && GetTickCount() - lastPacketTick < TIMEOUT_MS;
}

static DWORD WINAPI receiver(LPVOID unused) {
    WSADATA wsa;
    struct sockaddr_in local;
    DWORD timeout = 500, lastHello = 0;
    const char *env = getenv("XINPUT_BRIDGE_PORT");
    (void) unused;

    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0)
        return 1;

    memset(&appAddr, 0, sizeof(appAddr));
    appAddr.sin_family = AF_INET;
    appAddr.sin_port = htons((u_short) (env && atoi(env) > 0 ? atoi(env) : BRIDGE_DEFAULT_PORT));
    appAddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    local = appAddr;
    local.sin_port = 0;
    sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET || bind(sock, (struct sockaddr *) &local, sizeof(local)) != 0)
        return 1;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (const char *) &timeout, sizeof(timeout));

    for (;;) {
        unsigned char buf[64];
        int n;
        DWORD now = GetTickCount();

        if (lastHello == 0 || now - lastHello >= 1000) {
            sendto(sock, "XGPH", 4, 0, (struct sockaddr *) &appAddr, sizeof(appAddr));
            lastHello = now;
        }

        n = recv(sock, (char *) buf, sizeof(buf), 0);
        if (n != 16 || memcmp(buf, "XGP1", 4) != 0)
            continue;

        GAMEPAD p;
        p.wButtons = (WORD) (buf[4] | buf[5] << 8);
        p.bLeftTrigger = buf[6];
        p.bRightTrigger = buf[7];
        p.sThumbLX = (SHORT) (buf[8] | buf[9] << 8);
        p.sThumbLY = (SHORT) (buf[10] | buf[11] << 8);
        p.sThumbRX = (SHORT) (buf[12] | buf[13] << 8);
        p.sThumbRY = (SHORT) (buf[14] | buf[15] << 8);

        EnterCriticalSection(&lock);
        if (memcmp(&p, &pad, sizeof(p)) != 0) {
            pad = p;
            packetNumber++;
        }
        lastPacketTick = GetTickCount() ?: 1;
        LeaveCriticalSection(&lock);
        SetEvent(firstPacket);
    }
}

/* Started lazily: networking must not be initialized under the loader lock in DllMain. */
static void ensureStarted(void) {
    if (InterlockedCompareExchange(&started, 1, 0) != 0)
        return;
    HANDLE thread = CreateThread(NULL, 0, receiver, NULL, 0, NULL);
    if (thread) {
        CloseHandle(thread);
        /* Give the app a moment to answer, so the pad is present when the game first looks. */
        WaitForSingleObject(firstPacket, 300);
    }
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    (void) reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(instance);
        InitializeCriticalSection(&lock);
        firstPacket = CreateEventA(NULL, TRUE, FALSE, NULL);
    }
    return TRUE;
}

static DWORD getState(DWORD index, STATE *state, BOOL withGuide) {
    if (!state)
        return ERROR_BAD_ARGUMENTS;
    ensureStarted();
    if (index != 0)
        return ERROR_DEVICE_NOT_CONNECTED;

    EnterCriticalSection(&lock);
    BOOL ok = connected();
    if (ok) {
        state->dwPacketNumber = packetNumber;
        if (enabled)
            state->Gamepad = pad;
        else
            memset(&state->Gamepad, 0, sizeof(state->Gamepad));
        if (!withGuide)
            state->Gamepad.wButtons &= ~GUIDE_BUTTON;
    }
    LeaveCriticalSection(&lock);
    return ok ? ERROR_SUCCESS : ERROR_DEVICE_NOT_CONNECTED;
}

DWORD WINAPI XInputGetState(DWORD index, STATE *state) {
    return getState(index, state, FALSE);
}

DWORD WINAPI XInputGetStateEx(DWORD index, STATE *state) {
    return getState(index, state, TRUE);
}

DWORD WINAPI XInputSetState(DWORD index, VIBRATION *vibration) {
    unsigned char buf[8] = { 'X', 'G', 'P', 'V' };
    ensureStarted();
    if (index != 0 || !connected())
        return ERROR_DEVICE_NOT_CONNECTED;
    if (vibration && sock != INVALID_SOCKET) {
        WORD l = enabled ? vibration->wLeftMotorSpeed : 0, r = enabled ? vibration->wRightMotorSpeed : 0;
        buf[4] = l & 0xFF; buf[5] = l >> 8;
        buf[6] = r & 0xFF; buf[7] = r >> 8;
        sendto(sock, (const char *) buf, sizeof(buf), 0, (struct sockaddr *) &appAddr, sizeof(appAddr));
    }
    return ERROR_SUCCESS;
}

DWORD WINAPI XInputGetCapabilities(DWORD index, DWORD flags, CAPABILITIES *caps) {
    (void) flags;
    if (!caps)
        return ERROR_BAD_ARGUMENTS;
    ensureStarted();
    if (index != 0 || !connected())
        return ERROR_DEVICE_NOT_CONNECTED;
    memset(caps, 0, sizeof(*caps));
    caps->Type = 1;    /* XINPUT_DEVTYPE_GAMEPAD */
    caps->SubType = 1; /* XINPUT_DEVSUBTYPE_GAMEPAD */
    caps->Gamepad.wButtons = 0xF3FF;
    caps->Gamepad.bLeftTrigger = caps->Gamepad.bRightTrigger = 0xFF;
    caps->Gamepad.sThumbLX = caps->Gamepad.sThumbLY = (SHORT) 0xFFC0;
    caps->Gamepad.sThumbRX = caps->Gamepad.sThumbRY = (SHORT) 0xFFC0;
    caps->Vibration.wLeftMotorSpeed = caps->Vibration.wRightMotorSpeed = 0xFFFF;
    return ERROR_SUCCESS;
}

void WINAPI XInputEnable(BOOL enable) {
    enabled = enable;
}

DWORD WINAPI XInputGetDSoundAudioDeviceGuids(DWORD index, GUID *render, GUID *capture) {
    ensureStarted();
    if (index != 0 || !connected())
        return ERROR_DEVICE_NOT_CONNECTED;
    if (render) memset(render, 0, sizeof(*render));
    if (capture) memset(capture, 0, sizeof(*capture));
    return ERROR_SUCCESS;
}

DWORD WINAPI XInputGetAudioDeviceIds(DWORD index, LPWSTR render, UINT *renderCount, LPWSTR capture, UINT *captureCount) {
    (void) render; (void) capture;
    ensureStarted();
    if (index != 0 || !connected())
        return ERROR_DEVICE_NOT_CONNECTED;
    if (renderCount) *renderCount = 0;
    if (captureCount) *captureCount = 0;
    return ERROR_SUCCESS;
}

DWORD WINAPI XInputGetBatteryInformation(DWORD index, BYTE type, BATTERY_INFORMATION *info) {
    (void) type;
    if (!info)
        return ERROR_BAD_ARGUMENTS;
    ensureStarted();
    if (index != 0 || !connected())
        return ERROR_DEVICE_NOT_CONNECTED;
    info->BatteryType = 1;  /* BATTERY_TYPE_WIRED */
    info->BatteryLevel = 3; /* BATTERY_LEVEL_FULL */
    return ERROR_SUCCESS;
}

DWORD WINAPI XInputGetKeystroke(DWORD index, DWORD reserved, KEYSTROKE *keystroke) {
    (void) reserved; (void) keystroke;
    ensureStarted();
    if ((index != 0 && index != 0xFF) || !connected())
        return ERROR_DEVICE_NOT_CONNECTED;
    return ERROR_EMPTY;
}

DWORD WINAPI XInputWaitForGuideButton(DWORD index, DWORD flags, LPVOID unknown) {
    (void) flags; (void) unknown;
    return index == 0 ? ERROR_SUCCESS : ERROR_DEVICE_NOT_CONNECTED;
}

DWORD WINAPI XInputCancelGuideButtonWait(DWORD index) {
    return index == 0 ? ERROR_SUCCESS : ERROR_DEVICE_NOT_CONNECTED;
}

DWORD WINAPI XInputPowerOffController(DWORD index) {
    return index == 0 ? ERROR_SUCCESS : ERROR_DEVICE_NOT_CONNECTED;
}
