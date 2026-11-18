# Claude Bubble

A floating companion for Android that lives on top of your other apps, like a Messenger chat head.
Tap it and Claude Duet pops open in a floating window; tuck it away and keep doing what you were doing.
Its look and personality borrow from Nova Apex: color themes, eye styles, blinking, moods and little reactions.

## What it does

- **Tap** the bubble: the **floating chat**, Claude Duet on the web (claude.ai/messages), grows out of the bubble
  over your apps. The bubble docks into the window's top bar, in its theme colors, and keeps reacting there.
  Tap the bubble, the ⌄ button or Back and the window folds back into it. It stays loaded and signed in while
  tucked, so an answer keeps coming in.
  - Drag the bubble or the top bar to move the whole window, the ◢ corner to resize it. ⟳ reloads, ↗ opens the app.
  - Dark mode: the window is dark and Claude's page shows its dark theme (set Claude's own appearance to
    System or Dark if it stays light). Your messages, and Claude's where the page shows them as bubbles,
    take the bubble's theme colors.
  - The chat shrinks to the space above your keyboard, so the send button stays in reach.
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

- The microphone only works while the floating chat is open and you tap the page's mic button
  (Android asks you to allow it the first time). The app lets go of it the moment you tuck the chat away,
  the screen turns off, or an alarm, ringtone or call starts, and never listens in the background.
  No wake word, no always-on listening. Your keyboard's mic button works too.
- The app never plays sound or asks for audio focus itself.
- The chat can't play sound unless you tap something, and goes quiet when you tuck it away or the screen turns off.
- The moment an alarm, ringtone or call starts, anything playing in the chat is paused.

## Customize (in the app)

The settings page is dark and split into four tabs:

- **Look**: theme (29, most from Nova Apex), core (Orb, Ring, Plasma, Jelly), eyes, size.
- **Motion**: personality (Quirky, Calm, Face off); resting motion (Float, Bounce, Breathe, Sway, Drift, Still);
  while you type or talk (Ripples, Sound wave, Glow, Orbiting dots); while Claude answers (Sparkles, Equalizer
  mouth, Spin ring, Shimmer); blinking; how the chat window opens (Grow, Pop, Unroll, Fade); speed
  (Slow, Normal, Fast). Picking an effect plays it on the preview, and "Try it" plays any of them.
- **Behavior**: what a tap does, which app to open, start on boot, fade when idle, reset the window, sign out.
- **More**: updates, how it works, and the alarm and mic rules.

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
