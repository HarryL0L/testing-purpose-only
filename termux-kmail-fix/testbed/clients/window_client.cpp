// An application with a real window that passes its window id when opening the wallet.
#include <KWallet>
#include <QApplication>
#include <QLabel>
#include <QTimer>
#include <cstdio>
int main(int argc, char **argv)
{
    QApplication app(argc, argv);
    QLabel win(QStringLiteral("pretend this is KMail"));
    win.setWindowTitle(QStringLiteral("FakeKMail"));
    win.resize(900, 600);
    win.show();
    QTimer::singleShot(1000, &app, [&]() {
        printf("app window id: %llu\n", (unsigned long long)win.winId()); fflush(stdout);
        auto w = KWallet::Wallet::openWallet(KWallet::Wallet::NetworkWallet(), win.winId(), KWallet::Wallet::Asynchronous);
        QObject::connect(w, &KWallet::Wallet::walletOpened, &app, [&](bool ok) { printf("walletOpened(%s)\n", ok ? "true" : "false"); fflush(stdout); app.exit(ok ? 0 : 1); });
    });
    QTimer::singleShot(40000, &app, [&]() { printf("HANG\n"); app.exit(2); });
    return app.exec();
}
