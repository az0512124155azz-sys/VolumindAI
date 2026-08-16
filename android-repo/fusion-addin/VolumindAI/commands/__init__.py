from .palette import entry as volumind
commands = [volumind]
def start():
    for command in commands: command.start()
def stop():
    for command in reversed(commands): command.stop()
