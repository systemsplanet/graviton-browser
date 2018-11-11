# Test plan

This file contains some manual test plans. At some point we should automate them, although
testing software like Graviton can be tricky. We'll need to develop an HTTP server that can
replay recorded interactions with real Maven repositories, and use lots of TestFX for the GUI,
mock filesystems and so on.

The raw markdown can be copy/pasted into an issue to create lightweight todo lists for a release.

# Pre-flight checklist

Build the packages for Mac and Windows. Alternatively use `gradle installDist` and enter the
`build/install/graviton/bin` directory.

Ensure there are no Graviton cache directories anywhere and remove them if so.

## Installers

- [ ] Make sure your Mac security settings are configured to be the default, i.e. Gatekeeper is active.
- [ ] Ensure there are no Graviton installs or data directories already. 
- [ ] Open the mac DMG and drag it to the Applications folder. Ensure the icon and DMG background look right.
- [ ] Start the app from the Applications folder and ensure it starts, that the menu bar shows the name Graviton.
- [ ] Click the Windows installer. Ensure it installs automatically and with no user interaction.
- [ ] Find the app in the start menu and start it. Shut it down.
- [ ] Go into the control panel and uninstall Graviton. Ensure the uninstall completes without errors (this is a common failure point!)

## CLI

- [ ] Run `graviton --help` and ensure a useful help output is created. On Windows, ensure it's in colour.
- [ ] Run `graviton --version` and ensure the version number is correct.
- [ ] Run `graviton com.github.ricksbrown:cowsay --cowthink moo!` and ensure it downloads and runs correctly.
- [ ] Run it again and ensure there's no download or other UI text this time, just the cow.
- [ ] Run `graviton --clear-cache com.github.ricksbrown:cowsay "Downloaded again!"` and ensure it re-downloads and runs like before.
- [ ] Run `graviton -r com.github.ricksbrown:cowsay moo` and ensure it checks for an update again.
- [ ] Edit the history file to set the time of the last update to more than 24 hours ago. Then run 
      `graviton --offline com.github.ricksbrown:cowsay moo` and ensure it doesn't check for an update even though 
      the history entry is old.

## GUI shell

- [ ] Start the GUI. Run `com.github.startbugs` and ensure it downloads properly, the main window disappears, SpotBugs starts.
- [ ] Ensure the history list populates with an app tile for it.
- [ ] Start `com.github.rohitawate:everest` and click cancel during the download. Make sure it stops.
- [ ] Start Everest again, this time, quit during the download and ensure Graviton properly quits.
- [ ] Start it for a third time and this time let it succeed.
- [ ] Right click on one of the tiles and ensure each item works.

## Online update

- [ ] Clear your cache. Start `net.plan99:tictactoe:1.0.1` - it should download an old version of the app. Now go into
      your history file and edit it so the version number is missing from the coordinate fragment field.
      This makes it look like the user just hasn't updated for a while and a new version has been released. 
      Start Graviton GUI. Now from the command line run `graviton --background-update`
      and check the log file to ensure it updated you to the latest version of tictactoe. Start the app
      in the GUI ensure it's now the latest version.