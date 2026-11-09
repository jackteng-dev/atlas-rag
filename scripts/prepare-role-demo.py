#!/usr/bin/env python3
"""Prepare a fresh fictional login demo; plaintext credentials remain in ignored work only."""
import argparse
import copy
import json
import os
import pathlib
import secrets
import bcrypt

ROOT=pathlib.Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser()
parser.add_argument('--instance',required=True)
parser.add_argument('--source-config',default='config/local-deployment.example.json')
parser.add_argument('--port',type=int,default=18081)
args=parser.parse_args()
if not args.instance.replace('-','').isalnum():raise SystemExit('Invalid instance')
source=(ROOT/args.source_config).resolve()
if not source.is_relative_to(ROOT):raise SystemExit('Configuration must stay in this repository')
config=copy.deepcopy(json.loads(source.read_text()))
if not 1024<=args.port<=65535:raise SystemExit('Unprivileged port required')
directory=ROOT/'work/role-demo'/args.instance
directory.mkdir(parents=True,exist_ok=False)
accounts=[];profiles={}
for key,label,roles,depts in [('reader','一般使用者',['VIEWER'],['engineering']),('hr','HR 人資',['HR'],['hr']),('admin','管理員',['ADMIN'],['hr','engineering']),('outsider','其他部門（隔離驗證）',['HR'],['engineering'])]:
    name='demo-'+key;password=secrets.token_urlsafe(20)
    accounts.append({'username':name,'passwordHash':bcrypt.hashpw(password.encode(),bcrypt.gensalt(12)).decode(),'roles':roles,'departments':depts})
    profiles[key]={'label':label,'username':name,'password':password}
config.update(instance=args.instance,port=args.port,store=str((directory/'store.json').relative_to(ROOT)),accountsFile=str((directory/'accounts.json').relative_to(ROOT)),collection='atlas_role_'+args.instance.replace('-','_'))
for name,data in [('accounts.json',accounts),('profiles.json',profiles),('config.json',config),('instance.json',{'base':f'http://127.0.0.1:{args.port}','profilesFile':str(directory/'profiles.json'),'configFile':str(directory/'config.json')})]:
    with os.fdopen(os.open(directory/name,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as file:json.dump(data,file,ensure_ascii=False,indent=2);file.write('\n')
# This output contains paths, never the plaintext passwords or bcrypt hashes.
print(json.dumps({'result':'PREPARED','instanceFile':str(directory/'instance.json'),'configFile':str(directory/'config.json'),'credentialsFile':str(directory/'profiles.json'),'storeIsNew':True,'fictionalDemoOnly':True}))
