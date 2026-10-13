# 1.0.3-dev の配布と署名

実機試験前の検証版（Prerelease）です。versionCode 4、versionName 1.0.3-dev。

- [Releaseページ](https://github.com/kgymk1-hub/TextEditor-Android/releases/tag/v1.0.3-dev)
- [署名済みAPK](https://github.com/kgymk1-hub/TextEditor-Android/releases/download/v1.0.3-dev/PocketTextEditor-release.apk)
- APKはAndroid 8.0以上向けです。AABは直接インストールできません。

## 旧版からの移行

元の署名鍵が現在の環境へ引き継がれず回収できないため、2026年10月13日に所有者の許可を受けて新しいRSA 4096ビット鍵を作成しました。有効期間は10,000日です。旧版1.0.0-devおよびDebug版とは署名が異なり、そのまま上書き更新できません。

**旧版を削除する前にアプリ内でバックアップを書き出し、保存できたことを確認してください。** 旧版をアンインストールして本APKをインストール後、バックアップを読み込みます。URI権限は引き継がれないため、外部ファイルは必要に応じて開き直してください。

## 公開証明書

証明書SHA-256：

```
796224e6df568f22837800ea80a57d2549734d3e4b5cd5421a63aab36a90fe48
```

公開証明書は `docs/signing-certificate-v1.0.3-dev.pem` です。旧証明書・旧APKはそのまま保持しています。APKのv2・v3署名、zipalign、AABのjarsigner検証を完了しました。Android用の自己署名証明書を使用しています。

## 添付と検証

署名済みAPK／AAB、秘密鍵を含まないソースZIP、JUnit／Lintレポート、公開証明書、検証結果、ビルド情報、配布説明、SHA256SUMS.txtを添付します。SHA-256一覧は一覧自身以外の各ファイルを対象とします。

自動テスト87件成功、失敗・エラー・スキップ0件。Debug／Release Lintエラー0件、既存警告各21件。実機試験・ストア公開は未実施です。

## 秘密鍵の保管

新しいJKSと資格情報はGit管理対象外の `work/private-signing/v1.0.3-dev/` に制限付きアクセス権で保管しています。秘密鍵・パスワード・非公開バックアップをGitHub、Release、ソースZIP、ログへ含めません。

**別の安全な非公開場所へのバックアップは未完了です。** 現環境だけを保管先にせず、所有者管理の永続的な非公開保管先へ移す必要があります。以後はこの新しい鍵を継続使用し、versionCodeを増やします。

既存の署名前APK／AABは `scripts/sign-artifacts.py` で署名できます。環境変数と手順は [RELEASE.md](RELEASE.md) を参照してください。

## 公開確認

2026年10月13日に公開完了。Release ID411472010、Draft=false／Prerelease=trueです。GitHub Actionsが添付10件のSHA-256とダウンロード内容を照合してから公開しました。公開ページとAPKダウンロードのHTTP 200、およびダウンロードしたAPKのSHA-256とv2／v3署名を再確認済みです。

[公開ワークフローの検証結果](https://github.com/kgymk1-hub/TextEditor-Android/actions/runs/38372388326)

APK SHA-256：`26097059e6fe616b59a235ca19b890725a10faec904518f3e4f9275dc40bf0d1`
