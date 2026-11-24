# ColdPlayersPlus: ColdMC's additions

ColdPlayersPlus is ColdMC's private build of **Fake Player Plugin** by Bill_Hub (https://github.com/Pepe-tf/Fake-Player-plugin-2.0). That plugin's code is Bill_Hub's and is under his proprietary licence, so **none of it is in this repository**. Only the code ColdMC wrote on top of it is here, so this does not build on its own: it needs the private build it plugs into.

- `src/me/bill/fakePlayerPlugin/coldmc/`: ColdMC's code. The package name matches the build it lives in.
  - `AutoJoin.java`, `AliasCommand.java`: bots that rotate in and out on random timers, and alts that follow a real player in and out (`res/auto-join.yml`, `/ajoin`).
  - `pvp/`: PvP bots that play Kit PvP. Each bot has a brain with human limits (`BotBrain`), a fight recorder (`CombatRecorder`), and a small neural network (`Mlp`) with its offline trainer (`Trainer`). Settings are in `res/pvp-bots.yml`.
- `tools/train_pvp_model.py`: trains the PvP model away from the server, using fights the servers recorded. Use `pull` or `pull-dev`, then `train`, `install-dev` and `stage`. The server only records fights and runs the finished model.

`res/auto-join.yml` here is an example. The live names are kept on the server.

The MIT licence in `LICENSE` covers only the ColdMC code in this repository. It grants nothing for Fake Player Plugin, which stays under Bill_Hub's own licence.
