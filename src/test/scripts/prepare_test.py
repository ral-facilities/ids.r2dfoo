#!/usr/bin/env python3
from __future__ import print_function
import sys
import os
from string import Template
import tempfile
from filecmp import cmp
import glob
import shutil
from zipfile import ZipFile
import subprocess

if len(sys.argv) != 5:
    raise RuntimeError("Wrong number of arguments")

propFile = sys.argv[1]
home = sys.argv[2]
containerHome = sys.argv[3]
icaturl = sys.argv[4]

subst = dict(os.environ)
subst['HOME'] = home

try:
    tmpf = tempfile.NamedTemporaryFile(delete=False)
    name = tmpf.name
    with open(name, "wt") as f:
        with open(propFile, "rt") as s:
            t = Template(s.read()).substitute(subst)
            print(t, end="", file=f)
        print("icat.url = %s" % icaturl, file=f)
        print("testHome = %s" % home, file=f)
    if (os.path.exists("src/test/install/run.properties") and 
        cmp(name, "src/test/install/run.properties")):
        print("run.properties has not changed - skipping IDS redeploy")
        sys.exit(0)
    print("Installing with %s" % propFile)
    shutil.copy(name, "src/test/install/run.properties")
finally:
    os.remove(name)

for f in glob.glob("src/test/install/*.war"):
    os.remove(f)

with open("src/test/install/setup.properties", "wt") as f:
    print("secure         = false", file=f)
    print("container      = Glassfish", file=f)
    print("home           = %s" % containerHome, file=f)
    print("port           = 4848", file=f)

with open("src/test/install/run.properties.example", "wt") as f:
    pass

shutil.copy(glob.glob("target/ids.r2dfoo-*.war")[0], "src/test/install/")
shutil.copy("src/main/scripts/setup", "src/test/install/")

with ZipFile(glob.glob("target/ids.r2dfoo-*-distro.zip")[0]) as z:
    with open("src/test/install/setup_utils.py", "wb") as f:
        f.write(z.read("ids.r2dfoo/setup_utils.py"))

with open("src/main/resources/logback.xml", "rt") as s:
    with open("src/test/install/logback.xml", "wt") as f:
        t = Template(s.read()).substitute(subst)
        print(t, end="", file=f)

p = subprocess.Popen(["./setup", "-vvv", "install"], cwd="src/test/install", 
    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
#p.wait()
out, err = p.communicate()
print("./setup install stdout: " + out.decode())
print("./setup install stderr: " + err.decode())
rc = p.returncode
if rc != 0:
    sys.exit(rc)