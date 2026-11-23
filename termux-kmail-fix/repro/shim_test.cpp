// Mimics what kwalletd6's SecretServiceClient does with libsecret.
//   mode "current": listCollections() as shipped in kwallet 6.32.0
//   mode "fixed":   listCollections() with the proposed refresh
// It opens the same wallet twice in a row without returning to the main loop,
// which is what happens when a second open() is already queued behind the first.
#include <libsecret/secret.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static bool g_fixed = false;

// --- reloadCollections() exactly as added by reload-collections-from-service.patch
static bool reloadCollections(SecretService *service, GError **error)
{
    GDBusProxy *proxy = G_DBUS_PROXY(service);
    GVariant *reply = g_dbus_connection_call_sync(g_dbus_proxy_get_connection(proxy),
                                                  g_dbus_proxy_get_name(proxy),
                                                  g_dbus_proxy_get_object_path(proxy),
                                                  "org.freedesktop.DBus.Properties",
                                                  "Get",
                                                  g_variant_new("(ss)", "org.freedesktop.Secret.Service", "Collections"),
                                                  G_VARIANT_TYPE("(v)"),
                                                  G_DBUS_CALL_FLAGS_NONE,
                                                  -1,
                                                  nullptr,
                                                  error);
    if (!reply) {
        return false;
    }

    GVariant *paths = nullptr;
    g_variant_get(reply, "(v)", &paths);
    g_dbus_proxy_set_cached_property(proxy, "Collections", paths);
    g_variant_unref(paths);
    g_variant_unref(reply);

    return secret_service_load_collections_sync(service, nullptr, error);
}
// ----------------------------------------------------------------------------

static bool listCollections(SecretService *service, std::vector<std::string> &out)
{
    GError *error = nullptr;
    if (g_fixed) {
        reloadCollections(service, &error);                             // patched
    } else {
        secret_service_load_collections_sync(service, nullptr, &error); // as in 6.32.0
    }
    if (error) { fprintf(stderr, "load failed: %s\n", error->message); return false; }
    GList *list = secret_service_get_collections(service);
    for (GList *l = list; l; l = l->next) {
        gchar *label = secret_collection_get_label(SECRET_COLLECTION(l->data));
        out.push_back(label ? label : "");
        g_free(label);
    }
    g_list_free_full(list, g_object_unref);
    return true;
}

// same decision as KWalletD::openInternal(): create when the label is not listed
static bool openWallet(SecretService *service, const char *wallet, int n)
{
    std::vector<std::string> wallets;
    if (!listCollections(service, wallets)) return false;
    bool known = false;
    for (auto &w : wallets) known = known || w == wallet;
    printf("open #%d: shim sees %zu wallet(s), '%s' %s\n", n, wallets.size(), wallet, known ? "is listed" : "is NOT listed -> create");
    if (!known) {
        GError *error = nullptr;
        SecretCollection *c = secret_collection_create_sync(service, wallet, nullptr, SECRET_COLLECTION_CREATE_NONE, nullptr, &error);
        if (error) { fprintf(stderr, "create failed: %s\n", error->message); return false; }
        if (c) g_object_unref(c);
    }
    // unlockCollection() -> retrieveCollection(): must find it by label
    wallets.clear();
    if (!listCollections(service, wallets)) return false;
    bool found = false;
    for (auto &w : wallets) found = found || w == wallet;
    printf("open #%d: lookup after that -> %s\n", n, found ? "found, open succeeds" : "NOT found, open returns -1");
    return found;
}

int main(int argc, char **argv)
{
    g_fixed = argc > 1 && !strcmp(argv[1], "fixed");
    bool spin = argc > 2 && !strcmp(argv[2], "spin");
    GError *error = nullptr;
    SecretService *service = secret_service_get_sync(SECRET_SERVICE_LOAD_COLLECTIONS, nullptr, &error);
    if (!service) { fprintf(stderr, "no service: %s\n", error ? error->message : "?"); return 2; }
    printf("--- mode: %s%s ---\n", g_fixed ? "fixed" : "current 6.32.0", spin ? ", main loop runs between opens" : ", second open already queued");
    bool a = openWallet(service, "kdewallet", 1);
    if (spin) { for (int i = 0; i < 200; i++) { g_main_context_iteration(nullptr, FALSE); g_usleep(1000); } }
    bool b = openWallet(service, "kdewallet", 2);
    g_object_unref(service);
    return (a && b) ? 0 : 1;
}
