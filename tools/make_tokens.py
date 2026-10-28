#!/usr/bin/env python3
"""Writes web/tokens.js and web/tokens.css from web/tokens.json.

webOS 3 and Tizen 2 have no CSS custom properties, so the stylesheet repeats each
theme as literal values. --check fails when the files on disk differ from a fresh
generation (CI, via tools/check.sh).
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'web', 'tokens.json')
JS = os.path.join(ROOT, 'web', 'tokens.js')
CSS = os.path.join(ROOT, 'web', 'tokens.css')


def load():
    with open(SRC, encoding='utf-8') as f:
        data = json.load(f)
    data.pop('$comment', None)
    return data


def js(data):
    # JSON is an ES5 object literal when every key is quoted.
    body = json.dumps(data, ensure_ascii=False, indent=2)
    return (
        '/* Generated from web/tokens.json by tools/make_tokens.py. Do not edit. */\n'
        'var T = ' + body + ';\n'
    )


def css(data):
    L = data['color']['light']
    D = data['color']['dark']
    A = data['color']['accent']
    typ = data['type']
    foc = data['focus']
    # 2px outline reads as the 1.5px dark edge on the rasterized stage; 1.5px outlines
    # disappear on a 720p panel.
    edge = '2px'
    return """/* Generated from web/tokens.json by tools/make_tokens.py. Do not edit.
   Literal colors: webOS 3 / Tizen 2 have no CSS custom properties. */
.numeral {{ color: {lnum}; }}
.night .numeral {{ color: {dnum}; }}
.numeral.on {{ color: {lnumon}; }}
.night .numeral.on {{ color: {dnumon}; }}

.rowname {{ color: {link}; font-size: {row}px; font-weight: {roww}; }}
.rowname .count {{ color: {link3}; font-weight: 500; }}
.night .rowname {{ color: {dink}; }}
.night .rowname .count {{ color: {dink3}; }}
.rowname.dim {{ color: {link2}; }}
.night .rowname.dim {{ color: {dink2}; }}
.rownext {{ color: {link2}; font-size: {cap}px; font-weight: 500; }}
.night .rownext {{ color: {dink2}; }}
.rownext .prev {{ color: {link3}; }}
.night .rownext .prev {{ color: {dink3}; }}

#hclock {{ color: {link}; font-size: 40px; font-weight: 500; }}
#hclock small {{ color: {link2}; font-size: {cap}px; font-weight: 500; }}
.night #hclock {{ color: {dink}; }}
.night #hclock small {{ color: {dink2}; }}

.card .name {{ font-size: {row}px; }}
.card .meta {{ color: {link2}; font-size: {cap}px; }}
.night .card .meta {{ color: {dink2}; }}

.card.focus::before {{ border-color: {ring}; outline-color: {ledge}; }}
.night .card.focus::before {{ outline-color: {dedge}; }}
.card.focus .img {{ box-shadow: {shadow}; }}

.k {{ color: {link}; background: {lgb}; }}
.night .k {{ color: {dink}; background: rgba(255, 255, 255, 0.07); }}
.k.focus {{ background: {fill}; color: {fink}; }}

.set-h {{ color: {link}; font-size: {head}px; }}
.night .set-h {{ color: {dink}; }}
.set-h small {{ color: {link2}; font-size: {cap}px; }}
.night .set-h small {{ color: {dink2}; }}
.sec {{ color: {link2}; }}
.night .sec {{ color: {dink2}; }}
.sec .n {{ color: {link3}; }}
.night .sec .n {{ color: {dink3}; }}
.sec.on {{ background: rgba(29, 29, 31, 0.06); color: {link}; }}
.night .sec.on {{ background: rgba(255, 255, 255, 0.12); color: {dink}; }}
.sec.focus {{ background: {fill}; color: {fink}; }}
.sec.focus .n {{ color: rgba(29, 29, 31, 0.55); }}
.sec-title {{ color: {link3}; font-size: {cap}px; }}
.night .sec-title {{ color: {dink3}; }}
.srow {{ color: {link}; }}
.night .srow {{ color: {dink}; }}
.srow .d, .srow .v {{ color: {link2}; }}
.night .srow .d, .night .srow .v {{ color: {dink2}; }}
.srow .v .chev {{ color: {link3}; }}
.night .srow .v .chev {{ color: {dink3}; }}
.srow.focus {{ background: {fill}; color: {fink}; }}
.srow.focus .d, .srow.focus .v {{ color: rgba(29, 29, 31, 0.7); }}
.srow.focus .v .chev {{ color: rgba(29, 29, 31, 0.4); }}
.srow .tog.on {{ background: {on}; }}
.set-foot {{ color: {link3}; font-size: {cap}px; }}
.night .set-foot {{ color: {dink3}; }}

.set-bg {{
  background: {ltop};
  background: -webkit-linear-gradient(top, {ltop}, {lbot});
  background: linear-gradient({ltop}, {lbot});
}}
.night .set-bg {{
  background: {dbot};
  background: -webkit-radial-gradient(15% 20%, circle, rgba(64, 72, 140, 0.45), rgba(0, 0, 0, 0) 70%), -webkit-radial-gradient(85% 90%, circle, rgba(30, 110, 120, 0.35), rgba(0, 0, 0, 0) 70%), -webkit-linear-gradient(top, #121318, {dbot});
  background: radial-gradient(circle at 15% 20%, rgba(64, 72, 140, 0.45), rgba(0, 0, 0, 0) 70%), radial-gradient(circle at 85% 90%, rgba(30, 110, 120, 0.35), rgba(0, 0, 0, 0) 70%), linear-gradient(#121318, {dbot});
}}
""".format(
        lnum=L['numeral'], dnum=D['numeral'], lnumon=L['numeral.focus'], dnumon=D['numeral.focus'],
        link=L['ink'], link2=L['ink.secondary'], link3=L['ink.tertiary'],
        dink=D['ink'], dink2=D['ink.secondary'], dink3=D['ink.tertiary'],
        row=typ['rowTitle']['size'], roww=typ['rowTitle']['weight'],
        cap=typ['caption']['size'], head=typ['headline']['size'],
        ring=L['focus.ring'], ledge=L['focus.ringEdge'], dedge=D['focus.ringEdge'],
        shadow=foc['shadow'], fill=L['focus.fill'], fink=L['focus.ink'],
        lgb=L['glass.bottom'], on=A['on'],
        ltop=L['bg.top'], lbot=L['bg.bottom'], dbot=D['bg.bottom'],
        edge=edge,
    )


def main():
    data = load()
    js_text, css_text = js(data), css(data)
    check = '--check' in sys.argv
    if check:
        same = open(JS, encoding='utf-8').read() == js_text and open(CSS, encoding='utf-8').read() == css_text
        if not same:
            sys.exit('web/tokens.js or web/tokens.css is out of date. Run: python3 tools/make_tokens.py')
        print('OK: tokens.js and tokens.css match web/tokens.json.')
        return
    with open(JS, 'w', encoding='utf-8') as f:
        f.write(js_text)
    with open(CSS, 'w', encoding='utf-8') as f:
        f.write(css_text)
    print('Wrote web/tokens.js and web/tokens.css.')


if __name__ == '__main__':
    main()
