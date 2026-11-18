# Claude Bubble

A floating companion for Android that lives on top of your other apps, like a Messenger chat head.
Tap it and Claude Duet pops open in a floating window; tuck it away and keep doing what you were doing.
Its look and personality borrow from Nova Apex: color themes, eye styles, blinking, moods and little reactions.

## What it does

- **Tap** the bubble: pops open the **floating chat**, Claude Duet on the web (claude.ai/messages) in a window
  over your apps. The bubble moves to the top while it's open, like Messenger. Tap the bubble, the ⌄ button or
  Back to tuck it away. It stays loaded and signed in while tucked, so an answer keeps coming in.
  - Drag the window's top bar to move it, the ◢ corner to resize it. ⟳ reloads, ↗ opens the app.
  - Sign in with your email and the code Claude sends. Google's sign-in button doesn't work inside an app window.
- **The bubble reacts to the chat**: it leans in and listens while you type or use your keyboard's mic,
  its mouth moves while Claude answers (even while tucked), and a dot appears when an answer finished while tucked.
- **Hold** the bubble: Floating chat, Look at my screen, Open the app, Pet, Customize, Hide bubble.
- **Look at my screen**: Android asks to allow a capture. The bubble hides, takes one screenshot and puts it in
  the floating chat's message box (or sends it to the app, if that's what a tap does), ready for your question.
- **Open the app**: Claude Duet (`com.anthropic.aibo`) by default, or the regular Claude app (`com.anthropic.claude`).
  Falls back to the other app, then the web.
- **Drag** it anywhere; it snaps to the nearest side. **Drag it onto the X** to turn it off. **Shake it** and it gets dizzy.
- **Updates**: the app checks GitHub (`ckwebb2187/claude-bubble` releases) when you open it and twice a day.
  When a newer version is out, tap Update and Android asks you to confirm once.

## Alarms and calls come first

- No microphone permission at all, so the app can never listen, in the foreground or background.
  To talk to Claude, use your keyboard's mic button.
- The app never plays sound or asks for audio focus itself.
- The chat can't play sound unless you tap something, and goes quiet when you tuck it away or the screen turns off.
- The moment an alarm, ringtone or call starts, anything playing in the chat is paused.

## Customize (in the app)

Theme (29, most from Nova Apex), core (Orb, Ring, Plasma, Jelly), eyes, blinking, size, personality
(Quirky, Calm, Face off), what a tap does, which app to open, start on boot, fade when idle.

## Install

1. Download the APK. If it saves as `.bin`, rename it to `.apk` in your Files app.
2. Open it and allow installing from that app if Android asks.
3. Open Claude Bubble, tap **Allow display over other apps**, then **Turn bubble on**.
4. Later updates install from inside the app (Updates section, or the update notification).

## Build

Android Studio: open this folder and Run. Or `./gradlew assembleDebug`.
Updates only install over each other when signed with the same key, so keep using the same keystore
(it is not in this repo). Release APKs are named `ClaudeBubble-<version>-code<versionCode>.apk`;
the updater relies on that name.

## Known limits

- How the floating chat detects typing and answering is a guess from watching the page's text.
  If claude.ai changes, the bubble may miss an answer or react late; the chat itself still works.
- Screenshots go into the chat by simulating a paste. If the page won't take it, it goes to the app instead.
- File uploads open Android's picker. Native pop-ups in the page (rare on claude.ai) may not show.
