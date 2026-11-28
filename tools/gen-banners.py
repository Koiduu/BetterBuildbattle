"""
Builds the mod's Banner Database: mod/core/src/main/resources/betterbattle/banners.json.

Every design here is original, drawn for Build Battle from Minecraft's banner patterns.
A design is: name | category | search tags | base colour | layers, where layers are
"pattern:colour" pairs using the short codes in AB below (at most 6 layers, the survival limit).
Patterns after the 1.8.9 set (globe, piglin, flow, guster) are fine: the 1.8.9 build hides those designs.

Letters, numbers and symbols are written once with "I" (ink) and "P" (paper) and come out in
several colour schemes.

Run:  python tools/gen-banners.py            (writes the JSON)
      python tools/gen-banners.py --check    (validates only)
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'mod', 'core', 'src', 'main', 'resources', 'betterbattle', 'banners.json')
VERSION = 1
MAX_LAYERS = 6

COLORS = ['white', 'light_gray', 'gray', 'black', 'brown', 'red', 'orange', 'yellow', 'lime', 'green', 'cyan',
          'light_blue', 'blue', 'purple', 'magenta', 'pink']
AB = {'bs': 'stripe_bottom', 'ts': 'stripe_top', 'ls': 'stripe_left', 'rs': 'stripe_right', 'cs': 'stripe_center',
      'ms': 'stripe_middle', 'drs': 'stripe_downright', 'dls': 'stripe_downleft', 'ss': 'small_stripes', 'cr': 'cross',
      'sc': 'straight_cross', 'bt': 'triangle_bottom', 'tt': 'triangle_top', 'bts': 'triangles_bottom',
      'tts': 'triangles_top', 'ld': 'diagonal_left', 'rud': 'diagonal_up_right', 'lud': 'diagonal_up_left',
      'rd': 'diagonal_right', 'mc': 'circle', 'mr': 'rhombus', 'vh': 'half_vertical', 'hh': 'half_horizontal',
      'vhr': 'half_vertical_right', 'hhb': 'half_horizontal_bottom', 'bl': 'square_bottom_left',
      'br': 'square_bottom_right', 'tl': 'square_top_left', 'tr': 'square_top_right', 'bo': 'border',
      'cbo': 'curly_border', 'gra': 'gradient', 'gru': 'gradient_up', 'bri': 'bricks', 'glb': 'globe',
      'cre': 'creeper', 'sku': 'skull', 'flo': 'flower', 'moj': 'mojang', 'pig': 'piglin', 'flw': 'flow',
      'gus': 'guster'}
COL = {'w': 'white', 'lg': 'light_gray', 'gy': 'gray', 'k': 'black', 'br': 'brown', 'r': 'red', 'o': 'orange',
       'y': 'yellow', 'li': 'lime', 'g': 'green', 'c': 'cyan', 'lb': 'light_blue', 'b': 'blue', 'pu': 'purple',
       'm': 'magenta', 'pk': 'pink'}

CATEGORIES = [
    ('flags', 'Flags'),
    ('letters', 'Letters'),
    ('numbers', 'Numbers'),
    ('symbols', 'Symbols & arrows'),
    ('patterns', 'Patterns'),
    ('nature', 'Nature & sky'),
    ('food', 'Food'),
    ('holidays', 'Holidays & party'),
    ('objects', 'Objects & signs'),
    ('mobs', 'Mobs & faces'),
    ('colors', 'Plain colours'),
]

designs = []


def col(c):
    return COL.get(c, c)


def add(cat, name, tags, base, layers):
    """layers: 'p:c p:c ...' with short codes."""
    ls = []
    for tok in layers.split():
        p, c = tok.split(':')
        ls.append([AB.get(p, p), col(c)])
    designs.append({'name': name, 'category': cat, 'tags': tags, 'base': col(base), 'layers': ls})


def table(cat, rows):
    for line in rows.strip().splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        name, tags, base, layers = [s.strip() for s in line.split('|')]
        add(cat, name, tags, base, layers)


def glyph(cat, name, tags, spec, schemes):
    """spec: ink layers; 'x!' paints paper; leading '^' starts on ink; a paper border is added unless '~'."""
    toks = spec.split()
    on_ink = bool(toks) and toks[0] == '^'
    if on_ink:
        toks = toks[1:]
    border = '~' not in toks
    toks = [t for t in toks if t != '~']
    for ink, paper, label in schemes:
        ls = []
        for t in toks:
            ls.append('%s:%s' % (t.rstrip('!'), paper if t.endswith('!') else ink))
        if border:
            ls.append('bo:' + paper)
        add(cat, name + label, tags + ' ' + ink + ' ' + paper, ink if on_ink else paper, ' '.join(ls))


# ---------------------------------------------------------------- Flags
table('flags', """
Germany | europe deutschland | r | ts:k bs:y
Netherlands | europe holland dutch | w | ts:r bs:b
Luxembourg | europe | w | ts:r bs:lb
Russia | europe asia | b | ts:w bs:r
Slovakia | europe | b | ts:w bs:r mr:r
Serbia | europe | b | ts:r bs:w
Croatia | europe | w | ts:r bs:b mc:r
Hungary | europe | w | ts:r bs:g
Bulgaria | europe | g | ts:w bs:r
Austria | europe | w | ts:r bs:r
Lithuania | europe baltic | g | ts:y bs:r
Latvia | europe baltic | r | ms:w
Estonia | europe baltic | k | ts:b bs:w
Armenia | europe asia | b | ts:r bs:o
Spain | europe espana | y | ts:r bs:r
France | europe | w | ls:b rs:r
Italy | europe italia | w | ls:g rs:r
Ireland | europe irish | w | ls:g rs:o
Belgium | europe | y | ls:k rs:r
Romania | europe chad | y | ls:b rs:r
Moldova | europe | y | ls:b rs:r mc:br
Portugal | europe | r | ls:g mc:y
Malta | europe | r | vh:w
Vatican | europe holy see | w | vh:y
Poland | europe polska | w | hhb:r
Ukraine | europe | b | hhb:y
Monaco | europe indonesia | r | hhb:w
Indonesia | asia monaco | r | hhb:w
Denmark | europe nordic scandinavia | r | sc:w
Sweden | europe nordic scandinavia | b | sc:y
Finland | europe nordic scandinavia | w | sc:b
Norway | europe nordic scandinavia | r | cs:w ms:w sc:b
Iceland | europe nordic | b | cs:w ms:w sc:r
Faroe Islands | europe nordic | w | cs:b ms:b sc:r
England | europe uk britain st george | w | sc:r
Scotland | europe uk britain saltire | b | cr:w
Wales | europe uk britain dragon | g | hh:w moj:r
Cornwall | europe uk | k | sc:w
United Kingdom | europe uk britain union jack | b | cr:w sc:r
Switzerland | europe swiss | r | sc:w ts:r bs:r bo:r
Georgia | europe asia | w | cs:r ms:r
Albania | europe eagle | r | flo:k
Europe | eu european union | b | cbo:y
United States | usa america stars stripes | w | ss:r tl:b
Canada | america maple | w | flo:r mc:r ls:r rs:r
Mexico | america | w | ls:g rs:r mc:br
Brazil | america brasil | g | mr:y mc:b
Argentina | america | w | ts:lb bs:lb mc:y
Colombia | america | b | hh:y bs:r
Venezuela | america | b | ts:y bs:r
Peru | america | w | ls:r rs:r
Bolivia | america | y | ts:r bs:g
Chile | america | r | hh:w tl:b
Costa Rica | america | w | ts:b bs:b ms:r
Jamaica | america caribbean | k | tt:g bt:g cr:y
Japan | asia nippon sun | w | mc:r
Bangladesh | asia | g | mc:r
Palau | oceania | lb | mc:y
Laos | asia | b | ts:r bs:r mc:w
South Korea | asia korea | w | cr:k mc:r
China | asia | r | tl:y bo:r
Vietnam | asia | r | mc:y
India | asia | w | ts:o bs:g mc:b
Pakistan | asia | g | ls:w mc:w
Thailand | asia | w | ts:r bs:r ms:b
Iran | asia middle east | w | ts:g bs:r mc:r
Israel | asia middle east | w | ts:b bs:b bo:w
United Arab Emirates | uae middle east | w | ts:g bs:k ls:r
Yemen | middle east | w | ts:r bs:k
Egypt | africa middle east | w | ts:r bs:k mc:y
Morocco | africa | r | mr:g
Algeria | africa | w | vh:g mc:r
Nigeria | africa | w | ls:g rs:g
Ghana | africa | y | ts:r bs:g mc:k
Ethiopia | africa | y | ts:g bs:r mc:b
Kenya | africa | w | ts:k bs:g ms:r mr:r
Gambia | africa | w | ts:r bs:g ms:b
Botswana | africa | lb | ms:k
Gabon | africa | y | ts:g bs:b
Sierra Leone | africa | w | ts:li bs:lb
Niger | africa | w | ts:o bs:g mc:o
Ivory Coast | africa cote d'ivoire | w | ls:o rs:g
Guinea | africa | y | ls:r rs:g
Mali | africa | y | ls:g rs:r
Senegal | africa | y | ls:g rs:r mc:g
Cameroon | africa | r | ls:g rs:y mc:y
Tanzania | africa | g | rud:lb dls:k
United Nations | un world | lb | glb:w
Rainbow | pride lgbt rainbow | li | hh:o ts:r ms:y bs:b
Pirate | jolly roger skull | k | sku:w bo:k
""")

# ---------------------------------------------------------------- Letters, numbers, symbols
SCHEMES = [('black', 'white', ''), ('white', 'black', ' (white)')]
LETTERS = {
    'A': 'ls rs ts ms', 'B': 'rs bs ts ms cbo! ls ~', 'C': 'ts bs ls', 'D': 'rs bs ts cbo! ls',
    'E': 'ls ts ms bs', 'F': 'ls ts ms', 'G': 'rs hh! ls ts bs', 'H': 'ls rs ms', 'I': 'cs ts bs',
    'J': 'ls hh! rs bs', 'K': 'drs hh! dls ls', 'L': 'ls bs', 'M': 'tt ls rs', 'N': 'ls rs drs',
    'O': 'ls rs ts bs', 'P': 'rs hhb! ls ts ms', 'Q': 'ls rs ts bs bo! br ~', 'R': 'drs ls ts ms',
    'S': 'ts bs drs', 'T': 'cs ts', 'U': 'ls rs bs', 'V': '^ tt! bs!', 'W': 'bts ls rs', 'X': 'cr',
    'Y': 'cs hh! tt', 'Z': 'ts bs dls',
}
DIGITS = {
    '0': 'ls rs ts bs dls', '1': 'cs tl bs', '2': 'rs hhb! ts dls bs', '3': 'rs ts ms bs',
    '4': 'ls hhb! rs ms', '5': 'ls hhb! ts drs bs', '6': 'rs hh! ls bs ms', '7': 'ts dls',
    '8': 'ls rs ts ms bs', '9': 'ls hhb! ts ms rs',
}
for ch, spec in LETTERS.items():
    glyph('letters', 'Letter ' + ch, 'alphabet text sign word ' + ch.lower(), spec, SCHEMES)
for ch, spec in DIGITS.items():
    glyph('numbers', 'Number ' + ch, 'digit text sign score ' + ch, spec, SCHEMES)
SYMBOLS = [
    ('Exclamation mark', '! punctuation', 'cs ms! bs!'),
    ('Plus', '+ add math', 'sc ts! bs!'),
    ('Minus', '- dash subtract math', 'ms'),
    ('Equals', '= math', '^ ts! bs! ms!'),
    ('Colon', ': punctuation time', '^ ts! bs! ms! ls! rs!'),
    ('Full stop', '. dot period punctuation', 'cs hh! ms!'),
    ('Slash', '/ divide', 'dls'),
    ('Arrow up', 'direction up', 'mr hhb! cs'),
    ('Arrow down', 'direction down', 'mr hh! cs'),
    ('Chevron left', '< direction less than', 'cr vh!'),
    ('Chevron right', '> direction greater than', 'cr vhr!'),
    ('Chevron up', '^ direction caret', 'cr hh!'),
    ('Chevron down', 'v direction', 'cr hhb!'),
    ('Pointer left', 'triangle direction back', '^ ld! lud!'),
    ('Pointer right', 'triangle direction play forward', '^ rd! rud!'),
    ('Times', 'x multiply cross no', 'cr'),
    ('Diamond', 'rhombus gem shape', 'mr'),
    ('Dot', 'circle point shape', 'mc'),
]
for name, tags, spec in SYMBOLS:
    glyph('symbols', name, tags, spec, SCHEMES)
table('symbols', """
Red cross | medical health hospital first aid | w | sc:r ts:w bs:w bo:w
Green cross | pharmacy medical health | w | sc:g ts:w bs:w bo:w
No entry | stop forbidden sign road | r | ms:w bo:w
Bullseye | target archery dart | w | bo:r mr:r mc:w
Warning | caution danger sign exclamation | y | cs:k ms:y bs:y bo:k
Hazard stripes | caution warning construction | y | ss:k bo:k
Heart | love valentine | pk | tt:r tts:pk bs:pk bo:pk
Red heart | love valentine | r | lud:w rud:w tts:w bo:w
""")

# ---------------------------------------------------------------- Patterns (decoration)
for c, nm in [('r', 'Red'), ('b', 'Blue'), ('g', 'Green'), ('y', 'Yellow'), ('pk', 'Pink'), ('k', 'Black'),
              ('o', 'Orange'), ('lb', 'Light blue'), ('pu', 'Purple'), ('c', 'Cyan')]:
    add('patterns', nm + ' awning', 'stripes market stall shop canopy tent ' + col(c), 'w', 'ss:' + c)
for a, b, nm in [('k', 'w', 'Black and white'), ('r', 'w', 'Red and white'), ('b', 'w', 'Blue and white'),
                 ('g', 'li', 'Green'), ('y', 'k', 'Yellow and black'), ('pk', 'w', 'Pink and white')]:
    add('patterns', nm + ' checker', 'checkerboard chess tiles floor race finish chequered', b,
        'vhr:%s tr:%s br:%s tl:%s bl:%s' % (a, b, b, a, a))
table('patterns', """
Brick wall | bricks house wall | r | bri:lg
Stone bricks | castle wall dungeon | lg | bri:gy
Dark bricks | wall nether | gy | bri:k
Sandstone bricks | desert egypt wall | y | bri:o
Quartz bricks | white wall modern | w | bri:lg
Wood planks | wooden floor wall | br | bri:o ss:br
Sky | blue day gradient | w | gra:lb
Night sky | dark night gradient | b | gra:k
Sunset | evening dusk gradient | o | gra:pu
Sunrise | morning dawn gradient | y | gra:o gru:pk
Ocean | sea water deep gradient | b | gru:c
Deep ocean | sea abyss gradient | b | gru:k
Fire | flames hot gradient | y | gru:r gra:o
Forest | trees woods gradient | li | gru:g
Candy fade | sweet pink gradient | w | gru:pk
Fade to black | shadow dark gradient | w | gru:k
Fog | mist grey gradient | gy | gra:w
Purple haze | magic gradient | m | gra:pu gru:b
Zigzag | chevron teeth | y | bts:k tts:k
Red zigzag | chevron teeth | w | bts:r tts:r
Bunting | party flags triangles festival | w | tts:r bts:b
Shark teeth | teeth jaws | k | tts:w bts:w
Diamond pattern | argyle rhombus | r | mr:w mc:r
Argyle | sweater diamond | b | cr:lb mr:lb mc:b
Plaid | tartan scottish | r | ss:k ms:k cs:k
Tablecloth | picnic gingham | w | ss:r ms:r
Tiles | bathroom kitchen floor | w | sc:lg bo:lg
Pinstripes | suit stripes | k | ss:gy
Candy stripes | barber pole | w | drs:r dls:r
Diagonal split | half triangle | w | rd:k
Gold frame | picture frame painting border | w | cbo:y bo:y
Wooden frame | picture frame painting | w | bo:br
Dark frame | picture frame | lg | bo:k
Ornate border | fancy curly | w | cbo:pu bo:pu
Lace | doily fancy | pk | cbo:w
Wavy blue | waves water | lb | cbo:b mc:b
Camouflage | army military camo | g | flo:br mr:li mc:g
Leopard | animal print spots | y | flo:br mc:o
Polka flower | floral wallpaper | pk | flo:w
Floral wallpaper | flowers vintage | w | flo:pk bo:w
Circuit board | tech computer chip | g | flw:li bo:g
Spiral | swirl hypnotic | w | flw:k
Vertical rainbow | rainbow stripes | y | ls:r rs:b cs:li bo:pu
""")

# ---------------------------------------------------------------- Nature & sky
table('nature', """
Sun | sunny day summer weather | lb | mc:y flo:y mc:y
Sun (sunburst) | sunny summer weather | y | mr:o mc:y
Half moon | night lunar | b | mc:w vhr:b
Full moon | night lunar | k | mc:lg flo:w mc:lg
Starry night | stars space night | k | flo:w bo:k gra:b
Cloud | sky weather cloudy | lb | mr:w ms:w hhb:lb
Rain | weather storm rainy | gy | ss:lb ts:gy hh:gy mc:w
Storm | thunder lightning weather | gy | gra:k dls:y
Snow | weather winter snowy | lb | flo:w gra:w
Tornado | storm weather wind | lg | tt:gy cs:gy bs:lg gus:k
Planet | space saturn | k | ms:o mc:y bo:k
Earth | world globe space planet | k | glb:b mc:g
Galaxy | space stars | k | mr:pu mc:m flo:w bo:k
Volcano | lava eruption mountain | lb | mr:br bt:br mc:r ts:lb bs:br
Mountain | hills peak | lb | mr:gy bt:gy bs:g
Snowy mountains | alps winter peak | lb | bt:w bts:w bs:w
Hills | grassland countryside | lb | bts:g hhb:g bs:li
Desert | sand dunes | y | gra:o bts:y bs:y
Beach | sand sea summer | y | hh:lb ms:c hh:lb
Island | tropical sea | c | mc:y cs:g ts:c tt:g
Ocean waves | sea water | b | bts:lb tts:c mc:lb
Water | sea lake river | b | gra:lb cbo:lb
Lava | magma nether hot | r | flo:o gru:y
Grass | lawn field ground | br | ts:g hh:g ms:li
Dirt | soil ground earth | br | flo:o bo:br
Sand | beach desert | y | flo:o mc:y
Stone | rock cave | gy | flo:lg mc:gy
Ice | frozen cold winter | lb | drs:w dls:w gra:w
Snowflake | winter snow christmas | lb | cr:w sc:w mc:lb
Tree | oak forest | lb | cs:br hh:g ts:li
Pine tree | spruce forest | lb | mr:g bt:g bs:br
Palm tree | tropical beach island | lb | cs:br tts:g mc:g bs:y
Cherry blossom | sakura tree pink spring | lb | cs:br hh:pk flo:m
Autumn tree | fall leaves | y | cs:br hh:o flo:r
Flower | spring garden | w | flo:r
Sunflower | flower yellow summer | lb | cs:g flo:y mc:br
Tulip | flower spring | lb | cs:g bt:g mc:r tts:lb
Daisy | flower spring | g | flo:w mc:y
Mushroom | fungus red | lb | cs:w hh:r tts:lb bs:g
Cactus | desert plant | lb | cs:g ms:g bs:y
Leaves | foliage bush hedge | g | flo:li bo:g
Vines | jungle plants | g | ss:li bo:g
Coral | reef ocean | c | flo:m mc:pk
Fish | ocean sea | lb | mr:o rs:lb mc:o
""")

# ---------------------------------------------------------------- Food
table('food', """
Watermelon | fruit summer | li | ss:g
Apple | fruit red | w | cs:br hhb:w mc:r bo:w
Orange (fruit) | fruit citrus | w | mc:o
Lemon | fruit citrus sour | w | mr:y mc:y
Lime slice | fruit citrus | li | mc:w mc:li cr:w sc:w
Cherry | fruit red | w | dls:g mc:r bs:w
Grapes | fruit wine | w | flo:pu mc:pu ts:w
Banana | fruit yellow | w | dls:y drs:y hhb:w
Pineapple | fruit tropical | g | hhb:y bri:o bts:y
Carrot | vegetable orange | w | tt:o cs:g hhb:w
Corn | maize vegetable | g | cs:y bri:y bo:g
Pizza | food fast italian pepperoni | y | flo:r bo:o
Burger | hamburger fast food | br | ts:o ms:g bs:o cbo:lb
Fries | chips fast food | r | ss:y ts:y hhb:r bo:r
Popsicle | ice cream lolly summer | lb | cs:br hh:pk bo:lb
Cake | birthday dessert party candle | lb | cs:w ts:lb hhb:pk bs:br
Chocolate bar | candy sweet dessert | br | sc:k bri:br bo:k
Cookie | biscuit dessert | o | flo:br mc:o
Donut | doughnut dessert | lb | flo:pk mc:lb
Cheese | dairy yellow | y | mc:o flo:y
Bread | loaf bakery | o | gra:br ss:y
Egg | breakfast fried | lb | mc:w mr:w mc:y
Bacon | breakfast | r | ss:w gra:br
Sushi | japanese fish rice | k | mc:w mc:o
Milk | drink dairy | w | ts:lb bo:lb
Juice | drink orange | o | ts:y gru:y
""")

# ---------------------------------------------------------------- Holidays & party
table('holidays', """
Jack o'lantern | halloween pumpkin spooky | o | cre:k
Pumpkin | halloween autumn harvest | o | ss:y cbo:lb
Spooky skull | halloween skeleton | k | sku:w
Spider web | halloween spider | k | cr:w sc:w mr:k mc:w
Witch hat | halloween magic | pu | bt:k bs:k bo:pu
Candy corn | halloween sweet | y | hh:o ts:w
Christmas tree | xmas holiday winter | w | cs:br hh:w mr:g mc:r bo:w
Present | gift christmas birthday box | r | sc:y ts:r bs:r cs:y
Blue present | gift birthday box | b | sc:w ts:b bs:b cs:w
Wreath | christmas door decoration | w | flo:g mc:w
Christmas bauble | ornament xmas decoration | g | mc:r ms:y ts:g
Easter egg | easter spring | lb | mc:pk mr:pk ms:y mc:pk
Love letter | valentine envelope mail | w | tt:pk bo:pk mc:r
Birthday balloon | party celebration | lb | mc:r cs:w hh:lb mc:r
Confetti | party celebration | w | flo:m flw:y bo:w
Party | celebration fun disco | m | flo:y mc:c bo:m
Fireworks | new year celebration night | k | flo:r mc:y cr:k mc:y
Disco ball | party dance club | m | mc:lg cr:w sc:w bo:m
New year | celebration midnight | k | flo:y gra:pu
St Patrick's | ireland clover shamrock | w | flo:g mc:li
Trophy | winner prize award cup | b | tt:y cs:y bs:y bo:b
Medal | winner prize award gold | b | ts:r mc:y bs:b
Crown | king queen royal | y | tts:pu hhb:pu mc:r
""")

# ---------------------------------------------------------------- Objects & signs
table('objects', """
Window | glass house | lb | sc:w bo:w
Dark window | glass night house | k | sc:br bo:br
Stained glass | church cathedral window | b | cr:y sc:r mr:pu bo:k
Door | wooden house entrance | br | ss:o bo:br mc:y
Red door | house entrance | r | ss:r bo:k mc:y
Bookshelf | library books | br | ss:r ms:br bo:br
TV screen | television tv monitor | k | gra:b bo:gy
Computer | pc monitor screen tech | lb | gra:b bo:gy bs:gy
Candle | light wax | k | cs:w tt:k mc:o bs:k
Lantern | light | k | mc:o sc:k bo:gy
Torch | fire light | k | cs:br hh:k mc:o
Key | lock door | lb | mc:y cs:y hh:lb mc:y
Sword | weapon knight battle | lb | cs:lg ms:br bs:lb cs:br
Shield | knight armor defense templar | w | sc:r cbo:lb
Pickaxe | mining tool | lb | tt:lg cs:br
Sailboat | ship sea boat | lb | bt:w bs:br gra:lb
Rocket | space ship launch | k | mr:lg bts:r cs:lg mc:r
Traffic light | road signal | k | ts:r bs:li ms:y bo:k
Stop sign | road | r | ms:w cbo:r
Road | street asphalt | gy | cs:y ss:gy ls:k rs:k
Railway | train track | gy | sc:br ss:br ls:lg rs:lg
Envelope | mail letter | w | tt:lg bo:lg
Music note | song music melody | w | cs:k ss:w bl:k bo:w bts:w tts:w
Camera | photo picture | gy | mc:k mc:lb bo:k
Paint palette | art painter | br | flo:r mc:y ls:b bo:br
Paint drip | art graffiti | w | tts:m gru:m
Map | treasure world | y | ss:o cr:r
Treasure chest | pirate gold | br | ms:y mc:y bo:k
Diamond gem | jewel treasure | w | mr:lb mc:c
Coin | gold money | k | mc:y mc:o bo:k
Hourglass | time sand timer | w | cr:br tt:y bt:y bo:br
Potion | magic brew alchemy | w | mc:m tt:w cs:lg bs:w
Magic | wizard spell sparkle | pu | flo:y mc:pu
Dice | game die | w | mc:k tl:k br:k bo:w
Game controller | gaming video game | lg | ls:k rs:k sc:gy bo:lg
Soccer ball | football sport | w | flo:k mc:w mr:k
Basketball | sport ball | o | sc:k cbo:o
Goal net | soccer football sport | g | sc:w ss:w bo:w
Police | cop law blue | b | ms:w ts:k bs:k
Hospital | medical health | w | sc:r ts:w bs:w bo:b
Battery | power energy charge | gy | ts:gy hhb:li ms:li bo:k
House | home building | lb | mr:r hhb:w bs:g
Tent | camping outdoor | lb | bt:o cs:br bs:g
Igloo | snow winter house | lb | mc:w bs:w cs:k hh:lb
Bridge | river | lb | ms:br ss:br hh:lb bs:b
Lighthouse | sea coast tower | b | cs:w ms:r ts:y bs:lg
""")

# ---------------------------------------------------------------- Mobs & faces
table('mobs', """
Creeper | minecraft mob explode | li | cre:k
Zombie | minecraft mob undead | c | cre:g ts:br
Skeleton | minecraft mob undead bones | lg | sku:k
Wither skeleton | minecraft mob nether | k | sku:gy
Piglin | minecraft mob nether pig | pk | pig:k
Pig | minecraft animal farm | pk | pig:m bo:pk
Enderman | minecraft mob end | k | ms:m
Spider | minecraft mob arachnid | k | ms:r cs:k
Slime | minecraft mob green | li | ls:g rs:g ms:li mc:g
Blaze | minecraft mob nether fire | y | flo:o mc:y ms:k
Breeze | minecraft mob wind | lb | gus:w
Mojang | minecraft logo | w | moj:r
Alien | space ufo green | k | cre:li bo:k
Bee | insect honey | y | ss:k ms:y ts:y bs:y bo:k
""")

# ---------------------------------------------------------------- Plain colours
NICE = {'light_gray': 'Light grey', 'gray': 'Grey', 'light_blue': 'Light blue'}
for c in COLORS:
    nm = NICE.get(c, c.replace('_', ' ').capitalize())
    add('colors', nm, 'plain solid colour color blank ' + c, c, '')
for c in COLORS:
    nm = NICE.get(c, c.replace('_', ' ').capitalize())
    add('colors', nm + ' (framed)', 'plain border frame colour color ' + c, c, 'bo:' + ('black' if c != 'black' else 'gray'))


# ---------------------------------------------------------------- Checks and output
MODERN_ONLY = {'globe', 'piglin', 'flow', 'guster'}
PATTERNS = set(AB.values())


def check():
    errors = []
    names = set()
    cats = {c for c, _ in CATEGORIES}
    for d in designs:
        key = d['name'].lower()
        if key in names:
            errors.append('duplicate name: ' + d['name'])
        names.add(key)
        if d['category'] not in cats:
            errors.append('bad category: %s' % d)
        if d['base'] not in COLORS:
            errors.append('bad base colour: %s' % d['name'])
        if len(d['layers']) > MAX_LAYERS:
            errors.append('too many layers (%d): %s' % (len(d['layers']), d['name']))
        for p, c in d['layers']:
            if p not in PATTERNS:
                errors.append('bad pattern %s: %s' % (p, d['name']))
            if c not in COLORS:
                errors.append('bad colour %s: %s' % (c, d['name']))
    return errors


if __name__ == '__main__':
    errs = check()
    for e in errs:
        print(e)
    counts = {}
    for d in designs:
        counts[d['category']] = counts.get(d['category'], 0) + 1
    modern = sum(1 for d in designs if any(p in MODERN_ONLY for p, _ in d['layers']))
    print('%d designs (%d modern-only): %s' % (len(designs), modern, ', '.join('%s %d' % kv for kv in counts.items())))
    if errs:
        sys.exit(1)
    if '--check' in sys.argv:
        sys.exit(0)
    lines = ['{', '  "version": %d,' % VERSION, '  "categories": [']
    lines.append(',\n'.join('    {"id": %s, "label": %s}' % (json.dumps(i), json.dumps(l)) for i, l in CATEGORIES))
    lines.append('  ],')
    lines.append('  "designs": [')
    lines.append(',\n'.join('    ' + json.dumps(d, ensure_ascii=False) for d in designs))
    lines.append('  ]')
    lines.append('}')
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print('wrote', os.path.relpath(OUT, ROOT))
