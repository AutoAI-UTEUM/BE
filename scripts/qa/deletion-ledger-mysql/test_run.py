"""No Docker/network: validate the synthetic harness boundary and workflow contract."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("synthetic_drill", Path(__file__).with_name("run.py"))
p = importlib.util.module_from_spec(spec); spec.loader.exec_module(p)


class GuardTests(unittest.TestCase):
    def setUp(self):
        self.env = {"GITHUB_ACTIONS":"true","RUNNER_OS":"Linux","RUNNER_ENVIRONMENT":"github-hosted",
                    "GITHUB_REPOSITORY":"AutoAI-UTEUM/BE","GITHUB_SHA":"a"*40,"GITHUB_RUN_ID":"123"}

    def test_rejects_local_self_hosted_wrong_repository_and_remote_docker(self):
        with patch.object(p.sys,"platform","linux"), patch.object(p.socket,"gethostname",return_value="synthetic-ci-host"):
            p.validate_host(self.env)
            for key,value in [("GITHUB_ACTIONS","false"),("RUNNER_ENVIRONMENT","self-hosted"),("RUNNER_OS","Windows"),
                              ("GITHUB_REPOSITORY","other/BE"),("GITHUB_SHA","bad"),("DOCKER_HOST","tcp://remote.invalid:2375"),
                              ("DOCKER_CONTEXT","remote"),("DOCKER_CERT_PATH","/synthetic/cert")]:
                with self.subTest(key=key), self.assertRaises(ValueError): p.validate_host(self.env|{key:value})

    def test_rejects_exact_live_hostname_even_with_ci_variables(self):
        with patch.object(p.sys,"platform","linux"),patch.object(p.socket,"gethostname",return_value="ip-172-31-40-63.ap-northeast-2.compute.internal"):
            with self.assertRaises(ValueError):p.validate_host(self.env)

    def test_default_invocation_does_not_run_any_subprocess(self):
        with patch("sys.argv",["run.py"]),patch.object(p.subprocess,"run",side_effect=AssertionError("No command allowed")),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(p.main(),0)
        self.assertEqual(json.loads(output.getvalue())["dockerCalls"],0)

    def drill(self,call):
        with patch.object(p.sys,"platform","linux"),patch.object(p.socket,"gethostname",return_value="synthetic-ci-host"):
            return p.Drill(p.ROOT/"main-service/build/synthetic-public-artifacts",self.env,call=call)

    def test_fixed_local_socket_and_unexpected_failure_has_no_retry(self):
        calls=[]
        def call(args,**kw):calls.append(args);return subprocess.CompletedProcess(args,1,b"",b"arbitrary synthetic stderr")
        d=self.drill(call)
        with self.assertRaises(ValueError):d.docker("info")
        self.assertEqual(len(calls),1);self.assertEqual(calls[0][:3],["docker","--host","unix:///var/run/docker.sock"])

    def test_cleanup_never_removes_unowned_containers(self):
        calls=[]
        def call(args,**kw):
            calls.append(args)
            if args[3]=="ps":out=b"f"*64
            elif args[3]=="inspect":out=json.dumps({"name":"/foreign","labels":{}}).encode()
            else:self.fail("Attempted mutation of unowned resource")
            return subprocess.CompletedProcess(args,0,out,b"")
        d=self.drill(call)
        with self.assertRaises(ValueError):d.cleanup()
        self.assertFalse(any("rm"in c for c in calls))

    def test_java_job_uses_only_new_database_namespace_and_env_names(self):
        calls=[]
        d=None
        def call(args,**kw):
            calls.append((args,kw))
            if args[3]=="inspect":out=json.dumps({"name":"/"+d.prefix+"-source","labels":{p.LABEL:d.op}}).encode()
            else:out=b'{"status":"synthetic"}'
            return subprocess.CompletedProcess(args,0,out,b"")
        d=self.drill(call);d.ids={"source":"a"*64};d.uuids={"source":"12345678-1234-1234-1234-123456789abc"}
        d.java("source","export")
        args,kw=calls[-1]
        self.assertEqual(args[args.index("--network")+1],"container:"+"a"*64)
        self.assertNotIn(d.password," ".join(args));self.assertIn("LEDGER_DB_PASSWORD",args)
        for flag in ["--read-only","--cap-drop","--security-opt","--memory","--cpus"]:self.assertIn(flag,args)
        self.assertNotIn("--privileged",args);self.assertNotIn("--publish",args)

    def test_fixture_unpack_rejects_path_traversal_without_compiling(self):
        parent=p.ROOT/"main-service/build/synthetic-unpack-tests";parent.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=parent) as temp:
            root=Path(temp);jar=root/"bad.jar"
            with zipfile.ZipFile(jar,"w") as z:z.writestr("BOOT-INF/classes/../../outside.txt","synthetic")
            with self.assertRaises(ValueError):p.prepare_fixture(jar,root/"artifact",call=lambda *a,**k:self.fail("No compile allowed"))

    def test_workflow_is_readonly_pr_only_and_cannot_publish_or_deploy(self):
        text=(p.ROOT/".github/workflows/deletion-ledger-mysql-ci.yml").read_text()
        for required in ["pull_request:","branches: [develop, main]","head.repo.full_name == github.repository","contents: read","persist-credentials: false","ubuntu-24.04","timeout-minutes: 20","run.py --execute"]:self.assertIn(required,text)
        for forbidden in ["workflow_dispatch:","\n  push:","packages: write","secrets.","environment: dev","environment: prod","build-push-action","ssh-action"]:self.assertNotIn(forbidden,text)


if __name__=="__main__":unittest.main()
