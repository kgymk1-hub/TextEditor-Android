# Pocket Text Editor Android 1.0.0-dev 配布用ファイル

実機試験前の検証版です。GitHub Releasesはアップロード認証エラーによりDraftのままです。

- [署名済みAPK](asset/PocketTextEditor-release.apk)：Android 8.0以上向け。
- [配布・署名の説明](RELEASE.md)
- [検証結果](検証結果.md)：自動テスト61件成功、Debug／Release Lintエラー0件。実機試験は未実施です。
- [ビルド情報](build-manifest.json)
- [公開署名証明書](signing-certificate.pem)
- [SHA-256一覧](SHA256SUMS.txt)

旧デバッグ版とは署名が異なります。旧版を削除する前にアプリ内でバックアップを出力し、署名済み版にインポートしてください。

既存の署名済みAPKを変更せず配置しています。秘密鍵・パスワード・非公開バックアップは含みません。署名鍵の別の安全な非公開場所へのバックアップ完了は確認できていません。
