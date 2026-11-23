// Mimics libkgapi's KWalletStorage::open(): ask for the network wallet name,
// open it asynchronously, wait for walletOpened(), then use a folder.
#include <KWallet>
#include <QCoreApplication>
#include <QGuiApplication>
#include <QElapsedTimer>
#include <QTimer>
#include <cstdio>

int main(int argc, char **argv)
{
    QGuiApplication app(argc, argv);
    const QString tag = argc > 1 ? QString::fromLocal8Bit(argv[1]) : QStringLiteral("async");
    const int giveUpSec = argc > 2 ? atoi(argv[2]) : 90;
    QElapsedTimer t; t.start();
    auto say = [&](const QString &m) { printf("[%6.1fs] %s: %s\n", t.elapsed() / 1000.0, qPrintable(tag), qPrintable(m)); fflush(stdout); };

    say(QStringLiteral("asking NetworkWallet()"));
    const QString name = KWallet::Wallet::NetworkWallet();
    say(QStringLiteral("NetworkWallet() = '%1'").arg(name));
    KWallet::Wallet *w = KWallet::Wallet::openWallet(name, 0, KWallet::Wallet::Asynchronous);
    if (!w) { say(QStringLiteral("openWallet returned null -> FAILED")); return 1; }
    say(QStringLiteral("openWallet(async) sent, waiting for walletOpened"));
    QObject::connect(w, &KWallet::Wallet::walletOpened, &app, [&](bool ok) {
        say(QStringLiteral("walletOpened(%1)").arg(ok ? "true" : "false"));
        if (!ok) { say(QStringLiteral("RESULT: FAILED (reported)")); app.exit(1); return; }
        if (!w->hasFolder(QStringLiteral("LibKGAPI"))) w->createFolder(QStringLiteral("LibKGAPI"));
        const bool f = w->setFolder(QStringLiteral("LibKGAPI"));
        say(QStringLiteral("setFolder -> %1").arg(f ? "ok" : "FAILED"));
        say(f ? QStringLiteral("RESULT: OK, sign-in would start now (browser opens)") : QStringLiteral("RESULT: FAILED"));
        app.exit(f ? 0 : 1);
    });
    QTimer::singleShot(giveUpSec * 1000, &app, [&]() { say(QStringLiteral("RESULT: HANG, no answer after %1 s (browser never opens)").arg(giveUpSec)); app.exit(2); });
    return app.exec();
}
