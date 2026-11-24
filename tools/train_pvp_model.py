#!/usr/bin/env python3
"""Train the ColdPlayersPlus PvP bot model on THIS computer from fights recorded on the servers.

The plugin on production only collects (plugins/ColdPlayersPlus/pvp-learning/observations/*.csv.gz)
and runs the finished model. Training happens here, with the trainer that is built into the same
jar (plain Java, no libraries), so the server never spends CPU on it.

    train_pvp_model.py pull                 fetch production's observations over FTP (read-only)
    train_pvp_model.py pull-dev             copy the dev server's observations
    train_pvp_model.py train [trainer opts] train on everything pulled so far -> outputs/pvp-training/combat-model.txt
                                            (warm-starts from the last model; passes extra options to the
                                            trainer, e.g. --epochs 40 --hidden 32,16 --force)
    train_pvp_model.py install-dev          put the trained model on the dev server (it is picked up within
                                            learning.watch-model-seconds, or /cpp pvp model reload)
    train_pvp_model.py stage                lay the model out for production in outputs/prod-staging/pvp-model/
                                            and print the dry-run upload command. The upload itself needs
                                            the owner's OK every time.

All data and models stay inside the vault: knowledge/runtime/outputs/pvp-training/.
Notes: knowledge/plugins/FakePlayerPlugin/coldplayersplus-pvp-bots.md
"""
import os
import shutil
import subprocess
import sys

# Lives in the ColdPlayersPlus repo at tools/; the vault is five levels up (plugins-src/ColdPlayersPlus/tools).
# Set COLDMC_VAULT to run it from anywhere else.
VAULT = os.environ.get('COLDMC_VAULT') or os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..', '..', '..'))
SERVER = os.path.join(VAULT, 'core', 'server')
JAR = os.path.join(SERVER, 'plugins', 'ColdPlayersPlus.jar')
JAVA = '/usr/local/opt/openjdk@25/bin/java'
WORK = os.path.join(VAULT, 'knowledge', 'runtime', 'outputs', 'pvp-training')
OBS = os.path.join(WORK, 'observations')
MODEL = os.path.join(WORK, 'combat-model.txt')
REMOTE = '/plugins/ColdPlayersPlus/pvp-learning/observations'
DEV_LEARNING = os.path.join(SERVER, 'plugins', 'ColdPlayersPlus', 'pvp-learning')
STAGE = os.path.join(VAULT, 'knowledge', 'runtime', 'outputs', 'prod-staging', 'pvp-model')
FTP = os.path.join(VAULT, 'knowledge', 'runtime', 'scripts', 'prod_ftp.py')


def pull():
    dest = os.path.join(OBS, 'production')
    os.makedirs(dest, exist_ok=True)
    subprocess.run([sys.executable, '-I', FTP, 'mirror', REMOTE, dest], check=True)


def pull_dev():
    src = os.path.join(DEV_LEARNING, 'observations')
    dest = os.path.join(OBS, 'dev')
    os.makedirs(dest, exist_ok=True)
    n = 0
    for name in sorted(os.listdir(src)) if os.path.isdir(src) else []:
        if name.endswith('.csv.gz') or name.endswith('.csv'):
            shutil.copy2(os.path.join(src, name), os.path.join(dest, name))
            n += 1
    print(f'{n} files -> {dest}')


def train(extra):
    if not os.path.isdir(OBS):
        sys.exit('no observations yet: run pull (production) or pull-dev first')
    os.makedirs(WORK, exist_ok=True)
    out = os.path.join(WORK, 'combat-model.new.txt')
    cmd = [JAVA, '-Xmx2g', '-cp', JAR, 'me.bill.fakePlayerPlugin.coldmc.pvp.Trainer', OBS, '--out', out]
    if os.path.exists(MODEL) and '--init' not in extra:
        cmd += ['--init', MODEL]
    cmd += extra
    print(' '.join(cmd))
    r = subprocess.run(cmd)
    if r.returncode != 0:
        sys.exit(f'trainer exited {r.returncode}: no new model (the previous one, if any, is unchanged)')
    if os.path.exists(MODEL):
        keep = os.path.join(VAULT, 'backups', 'ColdPlayersPlus', 'pvp-models')
        os.makedirs(keep, exist_ok=True)
        stamp = len(os.listdir(keep)) + 1
        shutil.copy2(MODEL, os.path.join(keep, f'combat-model-{stamp:03d}.txt'))
    os.replace(out, MODEL)
    print('model ->', MODEL)


def install_dev():
    if not os.path.exists(MODEL):
        sys.exit('no model yet: run train first')
    os.makedirs(DEV_LEARNING, exist_ok=True)
    tmp = os.path.join(DEV_LEARNING, 'combat-model.txt.new')
    shutil.copy2(MODEL, tmp)
    os.replace(tmp, os.path.join(DEV_LEARNING, 'combat-model.txt'))
    print('installed on dev; then mirror it: mirror_to_update.py core/server/plugins/ColdPlayersPlus/pvp-learning/combat-model.txt')


def stage():
    if not os.path.exists(MODEL):
        sys.exit('no model yet: run train first')
    dest = os.path.join(STAGE, 'plugins', 'ColdPlayersPlus', 'pvp-learning')
    os.makedirs(dest, exist_ok=True)
    shutil.copy2(MODEL, os.path.join(dest, 'combat-model.txt'))
    print('staged ->', dest)
    print('dry run:  python3 -I knowledge/runtime/scripts/prod_ftp.py upload', os.path.relpath(STAGE, VAULT))
    print('upload (only with the owner\'s OK): the same command with --yes --force. The plugin swaps the model in by itself.')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd, rest = sys.argv[1], sys.argv[2:]
    {'pull': lambda: pull(), 'pull-dev': lambda: pull_dev(), 'train': lambda: train(rest),
     'install-dev': lambda: install_dev(), 'stage': lambda: stage()}.get(cmd, lambda: sys.exit(__doc__))()
