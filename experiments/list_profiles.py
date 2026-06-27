#!/usr/bin/env python3
from __future__ import annotations
import json
import calibre_bootstrap  # noqa
from calibre.customize import profiles

def dump(classes):
    rows=[]
    for cls in classes:
        p=cls(None)
        rows.append({
            'short_name': p.short_name,
            'name': p.name,
            'screen_size': getattr(p, 'screen_size', None),
            'dpi': getattr(p, 'dpi', None),
            'fbase': getattr(p, 'fbase', None),
            'fkey': getattr(p, 'fkey', None),
        })
    return rows
print(json.dumps({'input_profiles': dump(profiles.input_profiles), 'output_profiles': dump(profiles.output_profiles)}, indent=2, default=str))
