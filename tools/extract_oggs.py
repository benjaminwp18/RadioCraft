import json, os, platform, shutil, sys

'''
    Copies audio files from indescript hashed folders to named sorted folders.
    You may need to change output path.
    https://minecraft.wiki/w/Tutorial:Sound_directory#Extracting_Minecraft_sounds_using_Python
'''

# Change this for a different subset of sounds (currently set to just record disks)
MC_SOUNDS = "minecraft/sounds/records/"

# Change this if you want to put the sound files somewhere else
OUTPUT_PATH = os.path.normpath(os.path.expandvars(os.path.expanduser(f"~/Desktop/sounds/")))

def latest_version(directory_ls: list[str]):
    def is_newer(v_old: str, v_new: str) -> bool:
        v_old, v_new = v_old.strip(".json"), v_new.strip(".json")
        if v_new == "pre-1.6":
            return False
        if v_old == "pre-1.6":
            return True

        # Handle 1.20+ json files
        if v_new.isdigit():
            v_new += '0'
        if v_old.isdigit():
            v_old += '0'

        return float(v_new) > float(v_old)

    latest = directory_ls[-1]

    for f in directory_ls:
        if is_newer(latest, f):
            latest = f

    return latest

# This section should work on any system as well
print("Your OS is " + platform.system())
if platform.system() == "Windows":
    MC_ASSETS = os.path.expandvars(r"%APPDATA%/.minecraft/assets")
elif platform.system() == "Darwin":  # macOS
    MC_ASSETS = os.path.expanduser(r"~/Library/Application Support/minecraft/assets")
else:
    MC_ASSETS = os.path.expanduser(r"~/.minecraft/assets")

# Find the latest json index file
MC_VERSION = latest_version(os.listdir(MC_ASSETS+"/indexes/"))
print("The latest found index.json file is " + MC_VERSION + "\n")

# These are unlikely to change
MC_OBJECT_INDEX = f"{MC_ASSETS}/indexes/{MC_VERSION}"
MC_OBJECTS_PATH = f"{MC_ASSETS}/objects"

with open(MC_OBJECT_INDEX, "r") as read_file:
    # Parse the JSON file into a dictionary
    data = json.load(read_file)

    # Find each line with MC_SOUNDS prefix
    files = {k : v["hash"] for (k, v) in data["objects"].items() if k.startswith(MC_SOUNDS)}

    # # Uncomment to extract all files.
    # files = {k : v["hash"] for (k, v) in data["objects"].items()}

    print("File extraction:")

    for fpath, fhash in files.items():
        # Ensure the paths are good to go for Windows with properly escaped backslashes in the string
        src_fpath = os.path.normpath(f"{MC_OBJECTS_PATH}/{fhash[:2]}/{fhash}")
        dest_fpath = os.path.normpath(f"{OUTPUT_PATH}/{fpath}")

        # Print current extracted file
        print(fpath)

        # Make any directories needed to put the output file into as Python expects
        os.makedirs(os.path.dirname(dest_fpath), exist_ok=True)

        # Copy the file
        shutil.copyfile(src_fpath, dest_fpath)
