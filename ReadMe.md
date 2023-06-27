# Logic chain:

## 1) Importing
The boot process starts at the imports folder. That folder is scanned, and everything in it is converted to be placed in the models folder.

## 2) Model initialization & resource pack creation
The model initialization and resource pack creation are done at the same time. The plugin goes through the models folder and exports both bbmodel and ffmodel files to the resource pack. At the same time, the models are listed so that they can be created via command or called via API.

## 3) Ya done kid

# Techniques used in this plugin:
All models are scaled up 4x and then the size is readjusted in code in order to extend the theoretical maximum size of the model

Leather Horse Armor is used to create models with a hue that can be influenced through code (i.e. for damage indications). The horse armor must be set to white to display the correct colors!

ModelEngine uses a specific system of IDs for the textures, but actually reads the textures sequentially from config. IDs are assigned here based on their position in the list of textures, following how ModelEngine does it.

Armor stands are used for the default static items. //todo: soon I'll have to implement the new alternative display system from MC 1.19.4+, it's way more efficient