from . import commands
from .lib import fusionAddInUtils as futil

def run(context):
    try: commands.start()
    except: futil.handle_error("Volumind AI start", True)

def stop(context):
    try:
        commands.stop()
        futil.clear_handlers()
    except: futil.handle_error("Volumind AI stop")
