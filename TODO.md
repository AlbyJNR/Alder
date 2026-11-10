# CAS to-do

Read this list before each new version. Do the open items first, or say why not. Move a finished item to Done with its version.

## Waiting on Alan

- [ ] Create a throwaway Stremio account and add the repository secrets `CAS_TEST_EMAIL` and `CAS_TEST_PASSWORD`. The CI screens job then photographs the signed-in screens on each build.
- [ ] Set Settings › Profile › Show everything to On for his own profile on each TV.
- [ ] Look at 1.50.1 to 1.55.0 on a TV: the open rail and its shade, the short CAS, the title page's Play word, the "This isn't playing right now" panel.
- [ ] Confirm the Fire TV launcher tile fills its box after a reinstall. If it does not: try a 1280 x 720 banner.

## Next (needs the screens job first, to be seen before it ships)

- [ ] Home opens on one title with Play in focus, the rows below it.
- [ ] One motion language: the same curve and time for focus, page change and the rail.
- [ ] Split MainActivity.kt by page.

## Ideas, not agreed

- [ ] "Watch again" row on Home: finished titles, newest first.
- [ ] A sleep timer in the player (stop after this episode, or after 30 / 60 minutes).
- [ ] "Are you still watching?" after three episodes with no press of the remote.
- [ ] Voice search through the Fire TV remote's microphone button.
- [ ] A "Not for me" choice on a title, to keep it out of Because you watched.
- [ ] Bigger text option for the whole app (one switch, for older eyes).
- [ ] A profile's Show everything protected by its PIN, so a simple profile stays simple.

## Done

- [x] 1.55.0: Add to My List by a held OK; Move row to top.
- [x] 1.54.0: the short CAS by default, Show everything, signed-in screens job.
- [x] 1.53.0: age rating and genre on Home's title line; the rail's first entry is Settings.
- [x] 1.52.0: finished titles leave Continue Watching; removal is not a block.
- [x] 1.51.0: a series' page always says the episode; Real-Debrid sign-out.
