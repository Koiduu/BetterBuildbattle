# Custom heads: setting up the skin account

Players paint heads at battle.aspir3.net/heads. You review each one in the admin page (Custom heads card).
Once you approve one, the server sets it as the skin of a **dedicated** Minecraft account. Mojang then signs
the texture, and the head appears under "My heads" in the mod's Head Database.

Use a separate account, not AspireBot. Uploads are automated and every drawing is that account's skin for a
minute, so if Mojang ever flags it, sightings keep working.

Nothing is uploaded before you approve it. Without the settings below, players can still paint and you can
still review. Approved heads just wait in the upload queue.

## 1. An Azure app (once)

1. Go to <https://portal.azure.com>, then **App registrations**, then **New registration**.
   - Name: anything, e.g. `Better Battle heads`.
   - Supported account types: **Personal Microsoft accounts only**.
   - Redirect URI: leave it empty.
2. In the new app, open **Authentication** and set **Allow public client flows** to **Yes**. This is the
   device code sign-in. Save.
3. Copy the **Application (client) ID**.
4. Mojang has to allow new apps to use the Minecraft services API. Send the client ID through Mojang's form at
   <https://aka.ms/mce-reviewappid>. Until it's approved, `bb skin-login` fails at the "Minecraft login" step.

## 2. `.env`

```
SKIN_MS_CLIENT_ID=<the Application (client) ID>
SKIN_TOKEN_KEY=<a long random string>
```

`SKIN_TOKEN_KEY` encrypts the account's refresh token in the database. Keep it like the other secrets. If you
change it, run `bb skin-login` again.

Then run `bb restart`.

## 3. Sign the skin account in (once)

```
bb skin-login
```

It prints a link and a code. Open the link in a private window, sign in with the **skin account's** Microsoft
account, and enter the code. That's all. From then on the server refreshes the sign-in by itself: Microsoft
hands out a new refresh token every time, and it is saved encrypted in the `skin_account` table. It is never
printed or written to `.env`.

The admin page shows which account is signed in and the uploader's last error. If it ever says the sign-in
broke (a password change, say), run `bb skin-login` again.

## How uploads work

- About one upload a minute. After a skin is set, nothing new is uploaded until the session server shows that
  skin signed (usually seconds, at most 10 minutes before it tries again).
- Failures back off 1, 2, 4 … minutes, up to an hour. When Mojang answers 429, the whole queue waits at least
  5 minutes (longer if Mojang says so).
- Identical drawings are uploaded once. Any later copy becomes ready straight away.
- Limits per player (in `api/src/lib/plans.ts`): Builder 5, Architect 20, Master Builder 40 heads. Pending,
  queued and ready heads count, rejected and deleted ones don't. Also at most 5 new heads per 24 hours.
