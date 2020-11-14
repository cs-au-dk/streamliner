#!/usr/bin/env python3

import os

from pathlib import Path
from subprocess import run, CalledProcessError

JAVA_8  = '/usr/lib/jvm/java-8-oracle-amd64'
JAVA_11 = '/usr/lib/jvm/java-1.11.0-openjdk-amd64'
JAVA_13 = '/usr/lib/jvm/java-1.13.0-openjdk-amd64'
ENV = dict(os.environ, JAVA_HOME=JAVA_13)

cwd = Path(__file__).parent

def findbest(glob):
	l = []
	best_depth = 10 ** 18
	for path in glob:
		depth = len(path.parts)
		if depth > best_depth: continue
		elif depth < best_depth:
			best_depth = depth
			l = []
		l.append(path)
	return l

with (cwd / 'repos.txt').open() as f:
	urls = [repo.split() for repo in f.read().splitlines() if not repo.startswith('#')]

def touch(path):
	with path.open('a'): pass

def run_with_java(command, cwd):
	for JAVA in (JAVA_13, JAVA_11, JAVA_8):
		try:
			return run(command.split(), check=True, cwd=cwd, env=dict(os.environ, JAVA_HOME=JAVA))
		except CalledProcessError as e:
			exc = e
	raise exc


(cwd / 'repos').mkdir(exist_ok=True)

for repourl, revision in urls:
	reponame = repourl.rsplit('/', 1)[1]
	repodir = cwd / 'repos' / reponame

	print(reponame)
	if not repodir.exists():
		run(f'git clone {repourl} {repodir}'.split(), check=True)
		run(f'git checkout {revision}'.split(), check=True, cwd=repodir)

		with open(repodir / '.gitignore', 'a') as gitignore:
			print('.built', file=gitignore)
			print('.libs', file=gitignore)

	built = repodir / '.built'
	libs = repodir / '.libs'
	if built.exists() and libs.exists(): continue
	poms = findbest(repodir.rglob('pom.xml'))
	gradlews = findbest(repodir.rglob('gradlew'))

	if not built.exists():
		classfiles = list(repodir.rglob('*.class'))
		#assert not classfiles, classfiles

		if poms:
			for pom in poms:
				print('Building', pom)
				run_with_java('mvn compile', pom.parent)
		elif gradlews:
			for gradlew in gradlews:
				print('Building', gradlew)
				run_with_java('bash gradlew classes --no-daemon -g gcache', gradlew.parent)

			touch(libs)
		else:
			print('How to build?')
			print(list(repodir.iterdir()))
			assert False

		touch(built)

	if not libs.exists():
		if poms:
			for pom in poms:
				print('Copying jars', pom)
				run_with_java('mvn install -DskipTests', pom.parent)
				lib_dir = (repodir / 'libs').resolve()
				run_with_java(f'mvn dependency:copy-dependencies -DoutputDirectory={lib_dir}', pom.parent)

			touch(libs)
		elif gradlews:
			assert False, 'Rebuild gradle instead'
		else:
			assert False

