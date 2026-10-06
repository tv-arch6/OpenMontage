# Compile stubs

Signature-only copies of the **existing project classes** the ads feature calls,
plus a verbatim copy of `CigramUI.java`, so `javac-check.sh` can type-check the
new sources without the app's third-party libraries (AdMob, ExoPlayer, Cast,
CircleImageView).

These files are **never** added to the Sketchware project: the real ones are
already there. A real source in `user-app/java/…` always shadows its stub here.

| stub | copied from | why |
|---|---|---|
| `CigramUI.java` | real file, verbatim | the ads kit builds on its colours, font and dialogs |
| `CigramUserData.java` | signatures only | account identity (token, login state) |
| `CigramUpdateChecker.java` | signatures only | the "التحقق من التحديثات" row |
| `CigramStage1InitProvider.java` | signatures only | `cigramFont()`; the real file is 529 KB and pulls in everything |
