"""Verify acquisition cleanup and archive invariants without a board."""
import gzip
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import Mock

from .hardware import Acquisition
from .peer import server_nic_prepared
from .finalize import Archive
from .import_loaded import digest


class Hardware(unittest.TestCase):
    def test_networkd_restored_server_nic(self):
        address=dict(family='inet',local='192.0.2.1',prefixlen=24)
        self.assertFalse(server_nic_prepared(dict(flags=[],addr_info=[]),'192.0.2.1/24'))
        self.assertTrue(server_nic_prepared(dict(flags=['UP'],addr_info=[address]),'192.0.2.1/24'))
        for state in (dict(flags=[],addr_info=[address]),dict(flags=['UP'],addr_info=[]),
                      dict(flags=['UP'],addr_info=[dict(address,local='192.0.2.2')])):
            with self.assertRaises(ValueError):server_nic_prepared(state,'192.0.2.1/24')

    def test_unloaded_uses_single_flow_on_both_roles(self):
        endpoint=dict(ip='192.0.2.1',mac='02:00:00:00:00:01',prefix=24)
        config=dict(worker_cpus=[44],generator_cpus=list(range(6,14)),flows=64,
                    dut_endpoint=endpoint,peer_endpoint=endpoint,fpga_endpoint=endpoint,
                    transfer_url='http://fixture',ptp_bgx=4,tracking_mib=1024)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for stage,flows in (('unloaded',1),('loaded',64)):
                request=dict(stage=stage,system='DPDK',workers=1,payload_bytes=8)
                (root/'request.json').write_text(json.dumps(request))
                acquisition=Acquisition(config,request,root,root)
                for role in ('client','server'):
                    self.assertEqual(acquisition.role(role)['flows'],flows)
                    self.assertEqual(acquisition.role(role,setup=True)['flows'],1)

    def test_loaded_never_captures_and_restores_on_health_failure(self):
        for healthy in (True,False):
            with self.subTest(healthy=healthy),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);stage=root/'stage';stage.mkdir()
                request=dict(stage='loaded',system='LH',workers=1,payload_bytes=8)
                (stage/'request.json').write_text(json.dumps(request))
                config=dict(worker_cpus=[44,45,46,47],generator_cpus=list(range(6,14)),flows=64)
                acquisition=Acquisition(config,request,stage,root)
                acquisition.ensure_lh=Mock(return_value={'fixture':True})
                acquisition.capture=Mock(side_effect=AssertionError('Loaded trace acquisition'))
                before=root/'before';after=root/'after';before.mkdir();after.mkdir()
                for path,count in ((before,2),(after,10)):
                    (path/'provenance.json').write_text(json.dumps(dict(activity=[dict(calls=count,cpu=45,tid=99)])))
                (stage/'health.json').write_text(json.dumps(dict(passed=healthy)))
                acquisition.h=Mock();acquisition.h.snapshot.side_effect=[before,after]
                acquisition.h.vio.return_value={'trace_stop':1,'_status':{}}
                acquisition.execute_roles=Mock(return_value=({'client':root/'client'},{'client':{'files':{}}}))
                if healthy:
                    result=acquisition.run()
                    self.assertEqual(result['worker_activity'],[8]);self.assertEqual(result['trace_readouts'],0)
                else:
                    with self.assertRaisesRegex(ValueError,'health'):acquisition.run()
                acquisition.capture.assert_not_called()
                self.assertEqual([call.args[0] for call in acquisition.h.vio.call_args_list],['configure','preserve'])
                state=json.loads((root/'hardware-state.json').read_text())
                self.assertEqual(state['safe'],healthy)

    def test_uninstrumented_unloaded_never_reads_trace(self):
        for system in ('LH','DPDK'):
            with self.subTest(system=system),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);stage=root/'stage';stage.mkdir()
                request=dict(stage='unloaded',system=system,workers=1,payload_bytes=8,
                             instrumentation='off',count=1,warmup=0)
                (stage/'request.json').write_text(json.dumps(request))
                config=dict(build_config_sha256={system:{'off':'compiled-out'}})
                acquisition=Acquisition(config,request,stage,root)
                acquisition.ensure_lh=Mock(return_value={'fixture':True})
                acquisition.ensure_dpdk=Mock()
                acquisition.capture=Mock(side_effect=AssertionError('RTT-only trace acquisition'))
                acquisition.h=Mock();acquisition.h.snapshot.return_value=root
                acquisition.h.vio.return_value={'trace_stop':1,'_status':{}}
                (stage/'health.json').write_text(json.dumps(dict(passed=True)))
                client=root/'samples.csv'
                client.write_text('request_id,benchmark,ok,rpc_status,warmup,client_call_enter_ns,client_rx_burst_ns\n1,echo8,1,0,0,100,250\n')
                acquisition.execute_roles=Mock(return_value=({'client':root},{'client':{'files':{}}}))
                result=acquisition.run()
                acquisition.capture.assert_not_called()
                self.assertEqual(result['cohort']['rows'][0]['rtt_ns'],150)
                self.assertNotIn('mac_pairs',result)
                self.assertFalse(result['tracing_compiled'])
                self.assertFalse(result['mac_timestamping'])
                self.assertEqual(result['trace_readouts'],0)
                self.assertEqual([c.args[0] for c in acquisition.h.vio.call_args_list],['configure','preserve'])

    def test_baseline_boot_failure_preserves_before_recovery(self):
        for error in (RuntimeError('boot failed'),subprocess.TimeoutExpired('boot',3600)):
            with self.subTest(error=type(error).__name__),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);stage=root/'stage';stage.mkdir()
                request=dict(stage='unloaded',system='DPDK',payload_bytes=8)
                (stage/'request.json').write_text(json.dumps(request))
                case=root/'case.json';case.write_text(json.dumps([dict(program=['fixture'])]))
                config=dict(boot_case=str(case),gateway='fixture',dut='fixture',owner='fixture')
                acquisition=Acquisition(config,request,stage,root)
                acquisition.h=Mock();acquisition.h.command.side_effect=error
                acquisition.capture=Mock()
                with self.assertRaises(type(error)): acquisition.ensure_dpdk()
                acquisition.capture.assert_called_once_with(exceptional=True)
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


if __name__=='__main__':unittest.main()
