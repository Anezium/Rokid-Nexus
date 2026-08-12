# Changelog

## Unreleased

- Add an optional **Voice + Write** mode. The listening notice can stop speech,
  open the Nexus phone keyboard, and submit the typed question with Enter.

## 1.4.2

- **Reminders, notes, timers and the calendar now work on Hermes.** A server that
  runs its own tools never hands one back to the phone, so the assistant asks for
  a Nexus phone tool in plain text and Nexus runs it — the same twelve tools every
  other provider gets, minus the Ink pages.

## 1.4.1

- **Hermes is a provider of its own.** Give Settings the `/v1` root of your Hermes
  API server and its key, and the assistant answers from it — camera included: a
  server that runs its own tools still gets to look through the glasses.
- One conversation is now one Hermes session, from the first question to the last,
  instead of a fresh session for every sentence.
- An existing **Custom** connection recognises itself as Hermes when the server
  says so; a plain OpenAI-compatible server is left exactly as it was.
- Conversations can now stay open for seven days before starting fresh.

## 1.4.0

- **Your phone calendar is a real tool too.** Ask the assistant to add an
  appointment, read what is coming up, or delete one by its exact title and
  start time. Ambiguous matches and recurring series are refused instead of
  guessed.
- **Answers can become native interactive pages.** The assistant can author a
  strict Ink page or choose one of seven bounded templates for results that
  benefit from layout, charts, progress, or an action. The same phone compiler,
  native glasses renderer, typed limits, and `ink_surface` grant used by public
  plugins apply; plain text/card output remains the fallback.
- **The answer no longer goes dark during handover.** Listening, processing,
  and the final card or Ink page now share one display episode. Its scoped wake
  lock survives the band-to-surface morph and is released when that episode
  actually ends.

## 1.3.0

- **Ask it to remember, and it will.** Say "remind me in twenty minutes to
  check the oven", "set a timer for ten minutes", or "take a note that the
  spare key is under the pot" — the assistant schedules it, and at the moment
  it comes due your phone notifies you and the glasses raise the reminder,
  waking the display if it went dark. If the glasses are away, the phone still
  tells you and the reminder waits on the HUD for your return.
- It can also list what is pending, cancel a reminder by name, and search your
  notes back — all out loud, mid-conversation.
- **Notes & reminders**, a new section in settings, is where all of it lands:
  read a note in full, delete one, cancel a reminder before it rings. Nothing
  leaves the phone.
- Android guards precise alarms and notifications behind explicit consent, so
  the screen offers both — and only while something is actually missing. Without
  them a reminder still arrives, just later or only on the glasses; the
  assistant says so rather than pretending.
- Reminders survive a reboot, and one that came due while the phone was off is
  delivered once you turn it back on.
- **Every provider gets the new abilities**, not just ChatGPT: notes, reminders
  and timers are text, so a model that cannot see photos is no longer cut off
  from tools it could use perfectly well.
- Answers no longer show their formatting: models like to bold the time they
  just scheduled, and the glasses were rendering the asterisks.

## 1.2.0

- **Every provider can look now.** Photo questions were a ChatGPT-only power;
  API providers were told they could look and could only pretend. Now a
  vision-capable model on MiniMax, OpenRouter, GLM, DeepSeek, or your own
  server takes a real photo through the glasses camera — same one-photo-per-
  question rule, same privacy: never saved to the glasses' gallery.
- A model that cannot see images is now told so and answers honestly, instead
  of inventing tool-call syntax in the middle of its reply. Set the "can see
  photos" switch for your model in the provider's settings to enable the
  camera.
- A server that does not understand tools is retried once without them, so
  plain custom endpoints keep answering as before.

## 1.1.1

- **No more thinking out loud.** Models that reason inline — MiniMax, GLM,
  DeepSeek and anything similar behind OpenRouter or a custom server — were
  showing their raw `<think>` traces in every answer. Those blocks are now
  stripped from the stream before the glasses display them, the voice reads
  them, or the conversation keeps them: only the answer itself gets through.

## 1.1.0

- **Choose who answers.** Settings now opens on a provider list: your ChatGPT
  plan, an OpenAI key, OpenRouter, MiniMax (a Coding Plan key works as-is),
  DeepSeek, GLM (Z.ai), or any OpenAI-compatible server of your own. Each
  provider keeps its own key — encrypted on the phone — its own model choice,
  and its own endpoint, so switching is one tap, not a re-setup.
- **Any model id.** Pick from the suggestions or type the exact model your
  provider serves. For a model the app does not know, say whether it can see
  photos; photo questions are handled gracefully either way.
- **Give it a personality.** A new Personality box holds standing instructions
  — a persona, a tone, house rules — layered under the HUD formatting rules.
- Your notes and synced ChatGPT memories keep riding along with every question,
  whichever provider answers; the Memory toggle remains the single off switch.
- Signing out of ChatGPT no longer forgets the keys you saved for other
  providers.

## 1.0.1

- **Answers at full length.** The model is no longer told to stay under two
  short sentences: ask for detail and it answers as fully as the question
  deserves, in real paragraphs that the glasses render with hard line breaks
  instead of one flattened run of text. The notice band on the glasses grows
  and paginates to hold it — this needs Nexus 1.2.0 or later on both the
  phone and the glasses.
- While you dictate, the band keeps showing the tail of what it heard rather
  than paginating your own words away mid-sentence.

## 1.0.0

- First release: press the assist gesture, speak, and the answer streams onto
  the glasses. The stock Rokid assistant window is closed for you the moment it
  appears; your words transcribe live in a band on the HUD and the reply
  arrives in the same band, then is spoken aloud.
- **Sign in with ChatGPT.** Answers come from your own ChatGPT plan — nothing
  to paste, no per-token bill. An OpenAI API key works as the alternative
  route.
- **It can look.** Ask about what is in front of you and the model takes one
  photo through the glasses camera — one per question, only when the question
  needs eyes, and never saved to the glasses' own gallery.
- **Conversations continue.** A follow-up question keeps its context instead of
  starting over; a thread ends on its own after a quiet delay you choose. The
  phone keeps the transcripts — with the photos, if you want them kept — and
  both can be deleted at any time.
- **It starts out knowing you.** Memories and custom instructions from the
  ChatGPT account you signed in with are carried into the assistant, so you do
  not introduce yourself twice. Optional, synced automatically, and it can be
  turned off.
- **Web search built in.** The model decides when a question needs the web, so
  the weather and the news are answerable from your face.
- Pick the model (fastest to deepest) and how long it may think, in Settings.
- Requires Rokid Nexus 1.1.6 or newer, with *Show on your glasses*, *Glasses
  microphone*, *Speech to text*, *Text to speech*, *Replace the glasses
  assistant* and *Glasses camera* approved in Nexus → Settings → Plugin access.
