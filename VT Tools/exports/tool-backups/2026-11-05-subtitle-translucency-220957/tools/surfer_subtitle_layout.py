"""ASS card layout in explicit 1920x1080 coordinates, independent of font defaults."""
from functools import lru_cache
from html.parser import HTMLParser
import os
from pathlib import Path
import re

WIDTH, HEIGHT, MARGIN, PAD = 640, 1080, 20, 12
HEADER = '''[Script Info]
Title: Surfer telemetry card
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
ScaledBorderAndShadow: yes
WrapStyle: 2
YCbCr Matrix: TV.709

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Card,Arial,16,&H004D3217,&H004D3217,&H00FFFFFF,&H00FFFFFF,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
'''


class RichText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.styles=[('#17324d',False)]
        self.rows=[[]]

    def handle_starttag(self,tag,attrs):
        color,bold=self.styles[-1]
        if tag=='font':
            candidate=dict(attrs).get('color','')
            if re.fullmatch(r'#[0-9a-fA-F]{6}',candidate):color=candidate.lower()
        if tag=='b':bold=True
        self.styles.append((color,bold))

    def handle_endtag(self,tag):
        if len(self.styles)>1:self.styles.pop()

    def handle_data(self,data):
        for n,line in enumerate(data.split('\n')):
            if n:self.rows.append([])
            if line:self.rows[-1].append((line,*self.styles[-1]))


@lru_cache(maxsize=32)
def font(size,bold):
    from PIL import ImageFont
    name='arialbd.ttf' if bold else 'arial.ttf'
    try:return ImageFont.truetype(str(Path(os.environ.get('WINDIR','C:/Windows'))/'Fonts'/name),size*64)
    except OSError:
        return ImageFont.truetype('DejaVuSans-Bold.ttf' if bold else 'DejaVuSans.ttf',size*64)


@lru_cache(maxsize=32768)
def measure(text,size,bold):
    f=font(size,bold)
    # ASS font size is the ascender + descender height, rather than the em size.
    ascent,descent=f.getmetrics()
    return f.getlength(text)*size/(ascent+descent)


def color(value):
    return '&H'+value[5:7]+value[3:5]+value[1:3]+'&'


def literal(text):
    # User/log text must never become ASS override tags or newline escapes.
    return text.replace('\\','\\\u2060').replace('{','｛').replace('}','｝')


def timecode(ms):
    # ASS has centisecond precision. Round up so a new cue is never shown early.
    cs=(ms+9)//10
    return f'{cs//360000}:{cs//6000%60:02d}:{cs//100%60:02d}.{cs%100:02d}'


def event(layer,start,end,text):
    return f'Dialogue: {layer},{start},{end},Card,,0,0,0,,{text}\n'


class AssWriter:
    """Hold unchanged rows across frames; only clocks/age need per-frame events."""
    def __init__(self,stream):
        self.stream=stream
        self.pending={}

    def write(self,events):
        current={}
        for line in events.splitlines():
            row=line.split(',',9)
            current[(row[0],row[9])]=row
        for key,row in self.pending.items():
            following=current.get(key)
            if following is not None and row[2]==following[1]:
                following[1]=row[1]
            else:self.stream.write(','.join(row)+'\n')
        self.pending=current

    def flush(self):
        for row in self.pending.values():self.stream.write(','.join(row)+'\n')
        self.pending.clear()


def rectangle(x,y,width,height):
    return f'm {x:.2f} {y:.2f} l {x+width:.2f} {y:.2f} {x+width:.2f} {y+height:.2f} {x:.2f} {y+height:.2f}'


def layout(added,original,size):
    parser=RichText();parser.feed(added)
    source=RichText();source.feed(original)
    rows=[(row,size,True) for row in parser.rows]
    rows.append(([('Camera metadata','#64748b',True)],max(10,size-3),False))
    rows.extend(([(text,'#64748b',False) for text,_,_ in row],max(10,size-3),False) for row in source.rows if row)
    lines=[];y=MARGIN+PAD
    for row,font_size,highlights in rows:
        x=MARGIN+PAD;runs=[]
        for text,foreground,bold in row:
            background=None
            plain=''.join(part[0] for part in row)
            if highlights and bold:
                if foreground=='#a94700' and (plain.startswith(('Retreat active:','Gimbal tilt:')) or '[' in text):background='#fff0dc'
                if foreground=='#08752e' and (plain.startswith('Retreat active: YES') or '(selected)' in text or text.startswith('TILTING')):background='#e6f5e9'
                if foreground=='#8226a0' and plain=='Ride detected: YES':background='#f5e8fa'
            for word in re.findall(r'\s+|\S+',text):
                parts=[word] if measure(word,font_size,bold)<=WIDTH-2*PAD else list(word)
                for part in parts:
                    width=measure(part,font_size,bold)
                    if runs and x+width>MARGIN+WIDTH-PAD:
                        lines.append((y,font_size,runs));y+=font_size+3;x=MARGIN+PAD;runs=[]
                        if part.isspace():continue
                    runs.append((x,width,part,foreground,bold,background));x+=width
        if runs:lines.append((y,font_size,runs));y+=font_size+3
    return lines,y+PAD-MARGIN


def card_events(start_ms,end_ms,added,original):
    start,end=timecode(start_ms),timecode(end_ms)
    if start==end:return ''
    size=16
    lines,height=layout(added,original,size)
    while height>HEIGHT-2*MARGIN and size>8:
        size-=1;lines,height=layout(added,original,size)
    if height>HEIGHT-2*MARGIN:raise ValueError('Selected subtitle fields do not fit the card.')
    drawing=r'{\an7\pos(0,0)\bord0\shad0\p1\c'+color('#ffffff')+'}'+rectangle(MARGIN,MARGIN,WIDTH,height)
    result=[event(0,start,end,drawing)]
    backgrounds={}
    for y,font_size,runs in lines:
        text=r'{\an7\pos('+f'{MARGIN+PAD},{y}'+r')\q2\bord0\shad0\fs'+str(font_size)+'}'
        previous_style=None
        for x,width,part,foreground,bold,background in runs:
            if (foreground,bold)!=previous_style:
                text+=r'{\c'+color(foreground)+r'\b'+str(int(bold))+'}'
                previous_style=(foreground,bold)
            text+=literal(part)
            if background:backgrounds.setdefault(background,[]).append(rectangle(x,y,width,font_size+2))
        result.append(event(2,start,end,text))
    for background,rects in backgrounds.items():
        result.append(event(1,start,end,r'{\an7\pos(0,0)\bord0\shad0\p1\c'+color(background)+'}'+' '.join(rects)))
    return ''.join(result)


def compact_srt(original,added):
    number,timing,body=original.split('\n',2)
    # Preserve camera text; explicit size on every added font avoids player defaults.
    return number+'\n'+timing+'\n'+r'{\an7}'+body+'\n'+added+'\n\n'
