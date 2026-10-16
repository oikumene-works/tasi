"""Opt-in native UI helpers retained from the 2026-10-16 TA612C beta tests."""
from pathlib import Path
import json
import os
import re
import time
import gi

gi.require_version('Atspi', '2.0')
from gi.repository import Atspi

_configured_root = os.environ.get('TA612C_TEST_ROOT')
if not _configured_root:
    raise RuntimeError('Set TA612C_TEST_ROOT to the isolated trial directory.')
BASE = Path(_configured_root).expanduser().resolve()
if not (BASE / 'process.json').is_file():
    raise RuntimeError('The trial directory must contain its reviewed process.json.')
Atspi.set_timeout(1200, 1200)


def walk(node, depth=0):
    if depth > 23:
        return
    yield node
    for i in range(min(node.get_child_count(), 600)):
        yield from walk(node.get_child_at_index(i), depth + 1)


def application(pid=None):
    if pid is None:
        pid = json.loads((BASE / 'process.json').read_text())['pid']
    desktop = Atspi.get_desktop(0)
    return next(desktop.get_child_at_index(i)
                for i in range(desktop.get_child_count())
                if desktop.get_child_at_index(i).get_process_id() == pid)


def named(root, name):
    return next(node for node in walk(root) if node.get_name() == name)


def action(root, name):
    node = named(root, name)
    assert node.get_state_set().contains(Atspi.StateType.ENABLED), name
    return node.get_action_iface().do_action(0)


def serial_descriptors(pid):
    found = []
    for fd in (Path('/proc') / str(pid) / 'fd').iterdir():
        try:
            target = str(fd.readlink())
        except FileNotFoundError:
            continue
        if 'ttyUSB' in target or 'ttyACM' in target:
            found.append(target)
    return found


def compact_ui(root):
    rows = []
    for node in walk(root):
        role, name = node.get_role_name(), node.get_name()
        if role in ('frame', 'dialog', 'alert', 'combo box', 'page tab') or 'gathering' in name:
            item = {'role': role, 'name': name,
                    'enabled': node.get_state_set().contains(Atspi.StateType.ENABLED)}
            if role == 'combo box':
                try:
                    item['text'] = Atspi.Text.get_text(node, 0, -1)
                except Exception:
                    pass
            if role == 'page tab':
                item['selected'] = node.get_state_set().contains(Atspi.StateType.SELECTED)
            rows.append(item)
    return rows


def measurement_table(root):
    tables = [n for n in walk(root) if n.get_role_name() == 'table'
              and any(c.get_role_name() == 'table column header'
                      and c.get_name().startswith('Time') for c in walk(n))]
    visible = [n for n in tables if n.get_state_set().contains(Atspi.StateType.SHOWING)]
    if visible:
        tables = visible
    assert tables, 'No measurement table with a Time header was found.'
    node = max(tables, key=lambda n: n.get_table_iface().get_n_rows())
    table = node.get_table_iface()
    headers = [n.get_name() for n in walk(node)
               if n.get_role_name() == 'table column header']
    rows = []
    for row in range(table.get_n_rows()):
        values = []
        for column in range(table.get_n_columns()):
            cell = table.get_accessible_at(row, column)
            values.append(' '.join(n.get_name() for n in walk(cell)
                                   if n.get_name()).strip())
        rows.append(values)
    return {'headers': headers, 'rows': rows}


def select_tab(root, target):
    tabs = ['Graphic', 'Statistics', 'Table', 'Digital', 'File and Dataset Comment']
    selected = next(item['name'] for item in compact_ui(root)
                    if item['role'] == 'page tab' and item.get('selected'))
    distance = tabs.index(target) - tabs.index(selected)
    for _ in range(abs(distance)):
        assert action(root, 'next tabulator' if distance > 0 else 'previous tabulator')
        time.sleep(.2)


def select_record(root, target):
    text = next(item['text'].strip() for item in compact_ui(root)
                if re.match(r'^\d+\)', item.get('text', '').strip()))
    current = int(text.split(')', 1)[0])
    for _ in range(abs(target - current)):
        assert action(root, 'next record set' if target > current else 'previous record set')
        time.sleep(.2)
    selected = next(item['text'].strip() for item in compact_ui(root)
                    if re.match(r'^\d+\)', item.get('text', '').strip()))
    assert int(selected.split(')', 1)[0]) == target, selected
    return selected


def open_saved_osd(root, path):
    from ctypes import CDLL, c_char_p, c_void_p, c_ulong, c_uint, c_int
    from ctypes.util import find_library
    assert action(root, 'Open')
    chooser = None
    for _ in range(25):
        chooser = next((n for n in walk(root) if n.get_name() == 'Open ...'), None)
        if chooser is not None:
            break
        time.sleep(.2)
    assert chooser is not None
    files = next(n for n in walk(chooser)
                 if n.get_role_name() == 'table' and n.get_name() == 'Files')
    assert files.get_component_iface().grab_focus()
    x, t = CDLL(find_library('X11')), CDLL(find_library('Xtst'))
    x.XOpenDisplay.argtypes = [c_char_p]
    x.XOpenDisplay.restype = c_void_p
    x.XStringToKeysym.argtypes = [c_char_p]
    x.XStringToKeysym.restype = c_ulong
    x.XKeysymToKeycode.argtypes = [c_void_p, c_ulong]
    x.XKeysymToKeycode.restype = c_uint
    x.XSync.argtypes = [c_void_p, c_int]
    x.XCloseDisplay.argtypes = [c_void_p]
    t.XTestFakeKeyEvent.argtypes = [c_void_p, c_uint, c_int, c_ulong]
    display = x.XOpenDisplay(None)
    assert display
    ctrl = x.XKeysymToKeycode(display, x.XStringToKeysym(b'Control_L'))
    key = x.XKeysymToKeycode(display, x.XStringToKeysym(b'l'))
    for code, pressed in [(ctrl, 1), (key, 1), (key, 0), (ctrl, 0)]:
        t.XTestFakeKeyEvent(display, code, pressed, 0)
    x.XSync(display, 0)
    x.XCloseDisplay(display)
    time.sleep(.2)
    entries = [n for n in walk(chooser) if n.get_role_name() == 'text'
               and n.get_state_set().contains(Atspi.StateType.SHOWING)]
    assert len(entries) == 1
    assert entries[0].get_editable_text_iface().set_text_contents(str(path))
    time.sleep(.4)
    assert Atspi.Text.get_text(entries[0], 0, -1) == str(path)
    button = next(n for n in walk(chooser)
                  if n.get_role_name() == 'button' and n.get_name() == 'Open')
    assert button.get_action_iface().do_action(0)
    time.sleep(.8)
    assert any(path.name in n['name'] for n in compact_ui(root) if n['role'] == 'frame')
