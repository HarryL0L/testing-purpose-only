// Mimics what MailTransport / the IMAP resource do through QtKeychain:
// write a password, then read it back.
#include <qt6keychain/keychain.h>
#include <QGuiApplication>
#include <QElapsedTimer>
#include <QTimer>
#include <cstdio>

int main(int argc, char **argv)
{
    QGuiApplication app(argc, argv);
    const QString tag = argc > 1 ? QString::fromLocal8Bit(argv[1]) : QStringLiteral("keychain");
    const QString mode = argc > 2 ? QString::fromLocal8Bit(argv[2]) : QStringLiteral("write");
    QElapsedTimer t; t.start();
    auto say = [&](const QString &m) { printf("[%6.1fs] %s: %s\n", t.elapsed() / 1000.0, qPrintable(tag), qPrintable(m)); fflush(stdout); };

    if (mode == QLatin1String("write")) {
        auto job = new QKeychain::WritePasswordJob(QStringLiteral("mailtransports"));
        job->setKey(QStringLiteral("132516447"));
        job->setTextData(QStringLiteral("secret"));
        QObject::connect(job, &QKeychain::Job::finished, &app, [&, job]() {
            if (job->error()) { say(QStringLiteral("RESULT: write FAILED: %1").arg(job->errorString())); app.exit(1); }
            else { say(QStringLiteral("RESULT: write OK")); app.exit(0); }
        });
        say(QStringLiteral("writing password"));
        job->start();
    } else {
        auto job = new QKeychain::ReadPasswordJob(QStringLiteral("mailtransports"));
        job->setKey(QStringLiteral("132516447"));
        QObject::connect(job, &QKeychain::Job::finished, &app, [&, job]() {
            if (job->error()) { say(QStringLiteral("RESULT: read FAILED: %1").arg(job->errorString())); app.exit(1); }
            else { say(QStringLiteral("RESULT: read OK")); app.exit(0); }
        });
        say(QStringLiteral("reading password"));
        job->start();
    }
    QTimer::singleShot(120000, &app, [&]() { say(QStringLiteral("RESULT: HANG after 120 s")); app.exit(2); });
    return app.exec();
}
