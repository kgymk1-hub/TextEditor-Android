# 配布用ビルド

実機受入が完了するまでは検証版として扱います。現在の成果物は、インストールできるデバッグ署名APKと、所有者の署名を待つリリースAPK／AABです。

## 所有者の署名

所有者が管理する既存のAndroid署名鍵を使用してください。秘密鍵やパスワードはリポジトリ・ソースZIPに含めません。次の環境変数をローカル端末／秘密情報を保護できるCIに設定すると、同じビルド構成で署名済みAPK／AABを生成できます。

| 環境変数 | 値 |
| --- | --- |
| `POCKET_SIGN_STORE` | キーストアの絶対パス |
| `POCKET_SIGN_STORE_PASSWORD` | ストアのパスワード |
| `POCKET_SIGN_ALIAS` | 鍵のエイリアス |
| `POCKET_SIGN_KEY_PASSWORD` | 鍵のパスワード |

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease
```

鍵のパスだけ指定し、必要な秘密情報が足りない場合はビルドを停止します。変数を指定しなければ署名前の成果物を生成します。Android Studioの「Generate Signed Bundle / APK」でも署名できます。

APK署名はSDKの`apksigner verify --verbose app-release.apk`、AABはJDKの`jarsigner -verify app-release.aab`で確認します。デバッグ署名版から所有者署名版への更新は署名が異なるためできません。切替前にアプリ内のバックアップを書き出してください。

## ストア提出前

実機受入結果、所有者の公開名・連絡先・配布方針、公開時点のtargetSdk要件、アイコン・スクリーンショット・プライバシー説明、versionCodeの増加を確定します。アプリはアカウント・広告・解析SDK・独自通信機能を持たず、共有とSAFファイル操作は利用者の操作で行います。

この作業ではストアへの公開や所有者の署名鍵の作成・取得は行っていません。
