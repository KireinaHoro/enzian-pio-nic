"""Verify acquisition cleanup and archive invariants without a board."""
import gzip
import json
from pathlib import Path
import tempfile
import subprocess
import threading
import unittest
from unittest.mock import Mock, patch

from .hardware import Acquisition
from .peer import server_nic_prepared
from .finalize import Archive
from .import_loaded import digest


class Hardware(unittest.TestCase):
    def test_gateway_avoids_remote_home_and_preserves_argument_quoting(self):
        import shlex
        from .harness import Harness
        h=Harness({'gateway':'gateway'},Path('/tmp'));h.command=Mock()
        h.gateway(['python3','-c','print("a b")'],'probe')
        argv=h.command.call_args.args[0]
        self.assertEqual(argv[:2],['ssh','gateway'])
        self.assertTrue(argv[2].startswith('cd /tmp && '))
        self.assertEqual(shlex.split(argv[2][len('cd /tmp && '):]),['python3','-c','print("a b")'])

    def test_pdf_gate_uses_observed_system_canvas_and_rejects_wrong_height(self):
        from .adapter import pdf_checks
        from types import SimpleNamespace
        for count,height in ((1,180),(2,324),(3,468)):
            canvas=Mock(return_value=height/72)
            chart=SimpleNamespace(dimensions=lambda:dict(text=7,column=3),loaded_panel_height=canvas)
            with self.subTest(systems=count),tempfile.TemporaryDirectory() as tmp:
                def output(argv,**kwargs):
                    if argv[0]=='pdfinfo':return f'Pages: 1\nPage size: 504 x {height} pts\n'
                    return 'name type encoding emb sub uni object ID\n---\nFont Type1 Custom yes yes yes 7 0\n'
                with patch.dict('sys.modules',{'paper_chart':chart}),patch('subprocess.check_output',side_effect=output),patch('subprocess.run'):
                    result=pdf_checks(tmp,['echo-loaded-hot-latency-goodput'],loaded_system_count=count)
                    self.assertEqual(len(result),1)
                    canvas.assert_called_with(count)
                with patch.dict('sys.modules',{'paper_chart':chart}),patch('subprocess.check_output',return_value=f'Pages: 1\nPage size: 504 x {height+10} pts\n'):
                    with self.assertRaisesRegex(ValueError,'dimensions'):
                        pdf_checks(tmp,['echo-loaded-hot-latency-goodput'],loaded_system_count=count)

    def test_nested_capture_identity_is_unique_across_parent_attempts(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);labels=[]
            for attempt in (1,2):
                for chunk in (0,1):
                    path=root/f'attempt-{attempt}'/f'chunk-{chunk:03d}'
                    path.mkdir(parents=True);(path/'request.json').write_text('{"count":896}')
                    label=Acquisition({}, {}, path,root).label
                    self.assertEqual(label,Acquisition({}, {}, path,root).label)
                    labels.append(label)
            self.assertEqual(len(set(labels)),4)

    def test_native_service_uses_manifest_binary_identity(self):
        # Stop at staging: service construction must accept the public manifest
        # before any boot or hardware-state mutation occurs.
        for mode in ('off','breakdown'):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);(root/'request.json').write_text('{}')
                variant=dict(binary_path='/nix/store/app/bin/microbenchmarks',
                             binary_sha256='binary-sha',runtime='/nix/store/runtime',
                             tracing_compiled=mode=='breakdown')
                config=dict(native_variants={mode:variant},client_image_remote='/image',
                            client_image_sha256='image-sha',boot_case=str(root/'case.json'))
                (root/'case.json').write_text('[{"program":[]}]')
                request=dict(stage='unloaded',system='LH',payload_bytes=8,workers=1,
                             instrumentation=mode,count=10000,warmup=128)
                acquisition=Acquisition(config,request,root,root)
                acquisition.h=Mock()
                acquisition.h.stage.side_effect=RuntimeError('staging reached')
                with self.assertRaisesRegex(RuntimeError,'staging reached'):
                    acquisition.ensure_lh()
                acquisition.h.stage.assert_called_once()
                self.assertFalse((root/'hardware-state.json').exists())

    def test_programmer_is_source_owned_and_targets_configured_board(self):
        from .harness import Harness
        with tempfile.TemporaryDirectory() as directory:
            config=dict(programmer='programmer',vivado='/vivado',fpga_directory='/fpga',
                        gateway='gateway',cable='Digilent/NEWBOARD')
            harness=Harness(config,Path(directory));harness.command=Mock()
            argv=harness.program_command()
            self.assertEqual(argv[-4:],['gateway:3121','Digilent/NEWBOARD',
                                      '/fpga/shell_lauberhorn-eci.bit','/fpga/shell_lauberhorn-eci.ltx'])
            source=Path(directory)/'programmer-source.tcl'
            self.assertEqual(argv[argv.index('-source')+1],'/fpga/program-'+digest(source)+'.tcl')
            self.assertEqual(harness.command.call_count,1)

    def test_networkd_restored_server_nic(self):
        address=dict(family='inet',local='192.0.2.1',prefixlen=24)
        self.assertFalse(server_nic_prepared(dict(flags=[],addr_info=[]),'192.0.2.1/24'))
        self.assertTrue(server_nic_prepared(dict(flags=['UP'],addr_info=[address]),'192.0.2.1/24'))
        for state in (dict(flags=[],addr_info=[address]),dict(flags=['UP'],addr_info=[]),
                      dict(flags=['UP'],addr_info=[dict(address,local='192.0.2.2')])):
            with self.assertRaises(ValueError):server_nic_prepared(state,'192.0.2.1/24')

    def test_callback_delivery_is_reused(self):
        from . import adapter
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);a=root/'a';b=root/'b';a.mkdir();b.mkdir()
            config=dict(notify=['fixture'],thread='thread')
            with patch.object(adapter,'ROOT',root),patch.object(adapter,'callback_identity',return_value=config),patch.object(adapter.subprocess,'run',return_value=Mock(returncode=0,stderr='')) as send:
                adapter.verify_callback(config,a)
                adapter.verify_callback(config,b)
                self.assertEqual(send.call_count,1)
                self.assertTrue((b/'callback-reused.json').exists())
                (a/'callback-probe.json').write_text('{}')
                with self.assertRaisesRegex(ValueError,'evidence changed'):
                    adapter.verify_callback(config,b)
                self.assertEqual(send.call_count,1)

    def test_unloaded_uses_single_flow_on_both_roles(self):
        endpoint=dict(ip='192.0.2.1',mac='02:00:00:00:00:01',prefix=24)
        config=dict(worker_cpus=[44],generator_cpus=list(range(6,14)),flows=64,
                    dut_endpoint=endpoint,peer_endpoint=endpoint,fpga_endpoint=endpoint,
                    transfer_url='http://fixture',tracking_mib=1024)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for stage,flows in (('unloaded',1),('loaded',64)):
                request=dict(stage=stage,pipeline='prepared-dedicated-v1',slot_ns=1000,seed=1,max_send_lag_ns=2000,system='DPDK',workers=1,payload_bytes=8)
                (root/'request.json').write_text(json.dumps(request))
                acquisition=Acquisition(config,request,root,root)
                for role in ('client','server'):
                    self.assertEqual(acquisition.role(role)['flows'],flows)
                    self.assertEqual(acquisition.role(role,setup=True)['flows'],1)

    def test_loaded_never_captures_and_restores_on_health_failure(self):
        for healthy,workers in ((True,1),(True,4),(False,1)):
            with self.subTest(healthy=healthy,workers=workers),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);stage=root/'stage';stage.mkdir()
                request=dict(stage='loaded',system='LH',workers=workers,payload_bytes=8)
                (stage/'request.json').write_text(json.dumps(request))
                config=dict(worker_cpus=[44,45,46,47],generator_cpus=list(range(6,14)),flows=64)
                acquisition=Acquisition(config,request,stage,root)
                acquisition.ensure_lh=Mock(return_value={'fixture':True})
                acquisition.capture=Mock(side_effect=AssertionError('Loaded trace acquisition'))
                before=root/'before';after=root/'after';before.mkdir();after.mkdir()
                for path,count in ((before,2),(after,10)):
                    (path/'provenance.json').write_text(json.dumps(dict(activity=[dict(calls=count,cpu=45,tid=99)]+[dict(calls=0,cpu=0,tid=0)]*(workers-1))))
                (stage/'health.json').write_text(json.dumps(dict(passed=healthy)))
                acquisition.h=Mock();acquisition.h.snapshot.side_effect=[before,after]
                acquisition.h.vio.return_value={'trace_stop':1,'_status':{}}
                acquisition.execute_roles=Mock(return_value=({'client':root/'client'},{'client':{'files':{}}}))
                if healthy:
                    result=acquisition.run()
                    self.assertEqual(result['worker_activity'],[8]+[0]*(workers-1));self.assertEqual(result['trace_readouts'],0)
                else:
                    with self.assertRaisesRegex(ValueError,'health'):acquisition.run()
                acquisition.capture.assert_not_called()
                self.assertEqual([call.args[0] for call in acquisition.h.vio.call_args_list],['configure'])
                state=json.loads((root/'hardware-state.json').read_text())
                self.assertEqual(state['safe'],healthy)

    def test_parallel_cleanup_waits_for_both_and_propagates_failure(self):
        for fail in (False,True):
            with self.subTest(fail=fail),tempfile.TemporaryDirectory() as directory:
                root=Path(directory)
                (root/'request.json').write_text('{}')
                config=dict(peer='peer',dut='dut',dpdk_dut='dut',gateway='gateway',transfer_root='/transfer',
                            client_image_remote='/image',client_image_sha256='image-sha',
                            client_sha256='client-sha',server_sha256={'off':'server-sha'})
                acquisition=Acquisition(config,dict(system='DPDK',stage='unloaded'),root,root)
                acquisition.role=lambda role:dict(output='/dev/shm/echo-'+role)
                barrier=threading.Barrier(2,timeout=5)
                finished=[]
                def cpu(harness,machine,code,name):
                    barrier.wait()
                    finished.append(machine)
                    if fail and machine=='peer': raise RuntimeError('cleanup fixture')
                with patch('campaign.hardware.roles',return_value={'client':root,'server':root}),patch('campaign.hardware.validate_role',return_value={'files':{}}),patch('campaign.hardware.Harness.python',cpu),patch('campaign.hardware.Harness.gateway'):
                    if fail:
                        with self.assertRaisesRegex(RuntimeError,'cleanup fixture'):acquisition.execute_roles()
                    else:acquisition.execute_roles()
                self.assertCountEqual(finished,['peer','dut'])

    def test_loaded_freeze_reuse_requires_same_invocation_and_intact_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'request.json').write_text('{}')
            acquisition=Acquisition({}, {}, root,root)
            acquisition.h=Mock()
            acquisition.h.vio.return_value={'trace_stop':1}
            with patch.dict('os.environ',{'INVOCATION_ID':'service-one'}):
                acquisition.freeze_loaded()
                acquisition.freeze_loaded()
                self.assertEqual(acquisition.h.vio.call_count,1)
                (root/'loaded-freeze.json').write_text('{}')
                acquisition.freeze_loaded()
                self.assertEqual(acquisition.h.vio.call_count,2)
            with patch.dict('os.environ',{'INVOCATION_ID':'service-two'}):
                acquisition.freeze_loaded()
                self.assertEqual(acquisition.h.vio.call_count,3)
                acquisition.config={'changed':True}
                acquisition.freeze_loaded()
                self.assertEqual(acquisition.h.vio.call_count,4)
                acquisition.state={}
                acquisition.freeze_loaded()
                self.assertEqual(acquisition.h.vio.call_count,5)
            with patch.dict('os.environ',{},clear=True):
                acquisition.freeze_loaded()
                acquisition.freeze_loaded()
                self.assertEqual(acquisition.h.vio.call_count,7)

    def test_unloaded_never_reads_fpga_or_nic_timestamps(self):
        for system in ('LH','DPDK'):
            for mode in ('off','breakdown'):
                with self.subTest(system=system,mode=mode),tempfile.TemporaryDirectory() as directory:
                    root=Path(directory);(root/'request.json').write_text('{}')
                    request=dict(stage='unloaded',system=system,workers=1,payload_bytes=8,
                                 instrumentation=mode,count=1,warmup=0)
                    acquisition=Acquisition(dict(build_config_sha256={system:{mode:mode}}),request,root,root)
                    acquisition.ensure_lh=Mock(return_value={'fixture':True});acquisition.ensure_dpdk=Mock()
                    acquisition.h=Mock();acquisition.h.snapshot.return_value=root
                    acquisition.h.vio.return_value={'trace_stop':1}
                    (root/'health.json').write_text('{"passed":true}')
                    acquisition.capture=Mock(return_value=root/'capture');acquisition.last_recorder={'trace_stop':1}
                    acquisition.state['setup_count']=16
                    acquisition.execute_roles=Mock(return_value=({'client':root,'server':root},{'client':{'files':{}}}))
                    with patch('campaign.hardware.dpdk',return_value={}), patch('campaign.hardware.lauberhorn',return_value={}): result=acquisition.run()
                    self.assertFalse(result['nic_timestamping'])
                    self.assertFalse(result['wire_available'])
                    self.assertEqual(result['tracing_compiled'],mode=='breakdown')
                    self.assertEqual(result['trace_readouts'],0)
                    acquisition.capture.assert_not_called()
                    self.assertEqual(acquisition.h.vio.call_count,int(system=='LH'))

    def test_cpu_baseline_boot_failure_retains_logs_without_fpga_capture(self):
        for error in (RuntimeError('boot failed'),subprocess.TimeoutExpired('boot',3600)):
            with self.subTest(error=type(error).__name__),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);stage=root/'stage';stage.mkdir()
                request=dict(stage='unloaded',system='DPDK',payload_bytes=8)
                (stage/'request.json').write_text(json.dumps(request))
                case=root/'case.json';case.write_text(json.dumps([dict(program=['fixture'])]))
                config=dict(boot_case=str(case),gateway='fixture',dut='fixture',owner='fixture',dpdk_dut='zuestoll07',dut_interface='enP5p144s0np0')
                acquisition=Acquisition(config,request,stage,root)
                acquisition.h=Mock();acquisition.h.command.side_effect=error
                acquisition.capture=Mock()
                with self.assertRaises(type(error)): acquisition.ensure_dpdk()
                acquisition.capture.assert_not_called()
                acquisition.h.cpu.assert_not_called()
                self.assertFalse(json.loads((root/'hardware-state.json').read_text())['safe'])

    def test_ethernet_failure_stops_without_jtag_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);stage=root/'stage';stage.mkdir()
            request=dict(stage='unloaded',system='LH',payload_bytes=8)
            (stage/'request.json').write_text(json.dumps(request))
            config=dict(pipeline=519555,transfer_root='/fixture',transfer_url='http://fixture')
            acquisition=Acquisition(config,request,stage,root)
            acquisition.h=Mock();acquisition.h.capture.side_effect=RuntimeError('Ethernet failure')
            with self.assertRaisesRegex(RuntimeError,'Ethernet failure'): acquisition.capture()
            acquisition.h.capture.assert_called_once()
            acquisition.h.command.assert_not_called()
            acquisition.h.vio.assert_not_called()
            self.assertFalse(hasattr(acquisition,'jtag_fallback'))

    def test_archive_is_portable_and_checks_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'observations.csv';source.write_bytes(b'xid,value\n1,17\n')
            archive=Archive(root/'archive')
            result=archive.records([dict(files={str(source):digest(source)})])
            name,sha=next(iter(result[0]['files'].items()))
            self.assertFalse(Path(name).is_absolute())
            target=root/'archive'/name
            self.assertEqual(digest(target),sha)
            with gzip.open(target,'rb') as stream:self.assertEqual(stream.read(),source.read_bytes())
            bad=root/'bad.csv';bad.write_bytes(b'bad')
            with self.assertRaisesRegex(ValueError,'changed'):archive.file(bad,'0'*64)


    def test_fresh_deployment_has_no_inherited_recorder(self):
        from .adapter import initial_recorder
        harness=Mock()
        self.assertEqual(initial_recorder({'initial_state':'fresh'},harness,None),'fresh')
        harness.vio.assert_not_called()
        with self.assertRaisesRegex(ValueError,'cannot inherit'):
            initial_recorder({'initial_state':'fresh','previous_capture':'old-board'},harness,None)
        self.assertEqual(initial_recorder({'initial_state':'fresh'},harness,{'safe':True}),'resume')

if __name__=='__main__':unittest.main()
