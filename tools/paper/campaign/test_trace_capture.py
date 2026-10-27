"""Shared bounded Vivado retries and replay-safe control operations."""
import unittest
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[3]))


class Trigger(unittest.TestCase):
    def test_frozen_identity_allows_only_sticky_loss_assertion(self):
        from tools.enzian.trace.session import frozen_matches
        before=dict(trace_write_slot_1=100,trace_wrapped_1=0,trace_sample_lost_1=0,trace_dma_error_1=0)
        current=dict(trace_stop=1,_status=dict(before,trace_sample_lost_1=1))
        self.assertTrue(frozen_matches(before,current))
        for key in ('trace_write_slot_1','trace_wrapped_1','trace_dma_error_1'):
            self.assertFalse(frozen_matches(before,dict(current,_status=dict(current['_status'],**{key:before[key]+1}))))
        self.assertFalse(frozen_matches(before,dict(current,trace_stop=0)))
        self.assertFalse(frozen_matches(dict(before,trace_sample_lost_1=1),dict(trace_stop=1,_status=before)))


    def test_integrity_samples_cover_short_and_long_captures(self):
        import tempfile
        from tools.enzian.trace.control import Control
        for size in (64,1280,4096,8192):
            with tempfile.TemporaryDirectory() as directory:
                c=object.__new__(Control);c.directory=Path(directory);c.config={'programmer':'fixture'};c.remote='/fixture'
                data=bytes(range(256))*(size//256)+bytes(range(size%256))
                raw=Path(directory)/'raw';raw.write_bytes(data)
                sample=min(size,4096)
                (Path(directory)/'head.bin').write_bytes(data[:sample])
                (Path(directory)/'tail.bin').write_bytes(data[-sample:])
                c.call=lambda mode,**kwargs:None
                c.command=lambda *args:None
                result=c.check(raw)
                self.assertEqual(result['sample_bytes'],sample)
                self.assertTrue(result['head_matches'] and result['tail_matches'])

    def test_all_operations_share_bounded_retry(self):
        import tempfile
        from unittest.mock import patch
        from types import SimpleNamespace
        from tools.enzian.trace.control import Control
        for mode in ('configure','freeze','trigger','check','status','preserve'):
            for codes in ((139,0),(-11,139,0),(139,139,139),(1,),(255,)):
                with self.subTest(mode=mode,codes=codes), tempfile.TemporaryDirectory() as directory:
                    c=object.__new__(Control);c.directory=Path(directory)
                    c.remote='/fixture';c.setup=lambda:None;c.command=lambda *a:None
                    c.config=dict(vivado='vivado',hw_server='server',cable='cable',ltx='map',
                        peer_ip='192.0.2.1',peer_mac='00:00:00:00:00:01',programmer='fixture')
                    calls=[]
                    def run(argv,stdout,**kwargs):
                        calls.append(argv)
                        stdout.write('DIAG vio_output design/trace_stop 1\n')
                        return SimpleNamespace(returncode=codes[len(calls)-1])
                    with patch('tools.enzian.trace.control.subprocess.run',side_effect=run):
                        if codes[-1]==0:self.assertEqual(c._call(mode)['trace_stop'],1)
                        else:
                            with self.assertRaises(RuntimeError):c._call(mode)
                    self.assertEqual(len(calls),len(codes))
                    self.assertTrue(all(call==calls[0] for call in calls))
                    self.assertEqual(len(list(Path(directory).glob('operation-*.log'))),len(codes))

    def test_tcl_snapshot_and_trigger_replay(self):
        import tempfile,subprocess
        source=Path(__file__).resolve().parents[3]/'tools/enzian/trace/control.tcl'
        with tempfile.TemporaryDirectory() as directory:
            harness=Path(directory)/'test.tcl'
            harness.write_text("""
set values [dict create trace_stop 0 trace_dump_over_network 0 trace_dump_gateway_mac 0 trace_dump_server_ip 0 trace_dump_override_valid 0 trace_dump_local_ip 0 trace_dump_local_mac 0 x3 0 x4 0]
set edges 0
proc get_hw_probes {args} {
 global values
 set filter [lindex $args end]
 if {[string match {NAME == *} $filter]} {return [lindex $filter end]}
 if {[lsearch $args -filter]>=0} {return [lindex [split $filter /] end]}
 return [dict keys $values]
}
proc get_property {property object} {
 global values
 switch $property {
 NAME {return $object}
 TYPE {return vio_output}
 OUTPUT_VALUE {return [dict get $values $object]}
 INPUT_VALUE {return 0}
 }
}
proc set_property {property value object} {
 global values edges
 if {$property eq "OUTPUT_VALUE"} {
  if {$object eq "trace_dump_over_network" && [dict get $values $object]==0 && $value==1} {incr edges}
  dict set values $object $value
 }
}
foreach name {open_hw_manager connect_hw_server current_hw_target open_hw_target refresh_hw_device refresh_hw_vio commit_hw_vio close_hw_target after} {proc $name {args} {}}
proc get_hw_targets {args} {return target}
proc get_hw_devices {args} {return device}
proc get_hw_vios {args} {return vio}
""" + source.read_text().split('if {[catch {main}')[0] + """
set argv [list configure server cable ltx SNAPSHOT ip mac helper head tail 4096 c0a880eb 0c53310300eb]
main
if {[dict get $values trace_dump_local_ip] ne "c0a880eb" || [dict get $values trace_dump_local_mac] ne "0c53310300eb" || [dict get $values trace_dump_override_valid] ne "1b"} {error "identity override missing"}
set f [open SNAPSHOT r];set original [read $f];close $f
main
set f [open SNAPSHOT r];set repeated [read $f];close $f
if {$original ne $repeated} {error "snapshot replaced"}
set argv [lreplace $argv 0 0 trigger]
main
main
if {$edges!=1} {error "dump retriggered"}
set argv [lreplace $argv 0 0 preserve]
main
if {[dict get $values trace_dump_local_mac] ne "0" || [dict get $values trace_dump_local_ip] ne "0" || [dict get $values trace_stop] ne "1"} {error "identity restoration failed"}
""".replace('SNAPSHOT',str(Path(directory)/'snapshot')))
            result=subprocess.run(['tclsh',str(harness)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)


if __name__=='__main__':unittest.main()
