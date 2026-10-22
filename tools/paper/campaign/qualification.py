"""Bounded loaded-duration qualification; outputs never become paper cohorts.

Use the ordinary role harness on an already prepared, idle CPU-NIC host. LH
uses the matched board lifecycle. All acquisition evidence remains immutable.
"""
import argparse
import fcntl
import json
import math
from pathlib import Path
from .hardware import Acquisition, atomic
from .import_loaded import load
from .histogram import Latencies
from .policy import validate_activity_coverage


def window_statistics(windows, deadline_ns):
    counts=Latencies()
    transmitted=sum(w['transmitted'] for w in windows)
    on_time=sum(w['on_time'] for w in windows)
    for window in windows:
        counts.extend(Latencies((min(bucket*100+50,deadline_ns),n) for bucket,n in window['latency_histogram']))
    percentile=counts.population_percentile(99,transmitted) if transmitted else math.inf
    return dict(transmitted=transmitted,on_time=on_time,loss=(transmitted-on_time)/transmitted if transmitted else None,
                goodput_rpc_s=on_time/(sum(w['duration_ns'] for w in windows)/1e9),
                p99_us=percentile/1000 if math.isfinite(percentile) else None,
                p99_infinite=not math.isfinite(percentile),
                lag_mean_us=sum(w['lag_sum_ns'] for w in windows)/sum(w['lag_count'] for w in windows)/1000 if transmitted else None,
                lag_max_us=max(w['lag_max_ns'] for w in windows)/1000)


def temporal_statistics(trial):
    windows=trial['windows'];width=trial['window_ns']
    if width!=1_000_000_000 or trial['duration_ns']%10_000_000_000:
        raise ValueError('Duration qualification requires 1s windows and whole 10s blocks')
    return dict(whole=window_statistics(windows,trial['deadline_ns']),
                blocks_10s=[dict(start_s=i,**window_statistics(windows[i:i+10],trial['deadline_ns'])) for i in range(0,len(windows),10)])


class Qualification:
    def __init__(self, root, hardware, plan):
        self.root=Path(root);self.hardware=hardware;self.plan=plan
        self.root.mkdir(exist_ok=False)
        atomic(self.root/'plan.json',plan);atomic(self.root/'hardware.json',hardware)
        self.sequence=0

    def acquire(self, case, rate, duration_ms, seed):
        self.sequence+=1
        directory=self.root/f'{self.sequence:03d}-{case["system"]}-{case["payload_bytes"]}-{case["workers"]}-{rate}-s{seed}'
        directory.mkdir()
        request=dict(stage='loaded',**{k:case[k] for k in ('system','payload_bytes','workers')},
                     rate=rate,duration_ms=duration_ms,warmup_ms=self.plan['warmup_ms'],deadline_ms=10,
                     arrival_model='slotted-poisson',slot_ns=self.plan['slot_ns'],seed=seed,
                     max_send_lag_ns=self.plan['max_send_lag_ns'],window_ms=1000)
        if case['system']=='CS-A64':request['execution_model']='resident-workers'
        if self.hardware.get('aggregate',False):request['accounting_cpu']=self.hardware.get('accounting_cpu',8)
        atomic(directory/'request.json',request)
        acquisition=Acquisition(self.hardware,request,directory,self.root)
        if case['system']=='LH':
            result=acquisition.run();csv,summary=Path(result['csv']),Path(result['summary'])
            atomic(directory/'result.json',result)
            activity=result['worker_activity']
        else:
            # Checks kernel/driver and rejects any stale runtime modules. CPU-NIC
            # diagnostics do not reboot a healthy host or access FPGA traces.
            acquisition.ensure_caladan()
            acquisition.state['system']=case['system']
            atomic(acquisition.state_path,acquisition.state)
            paths,evidence=acquisition.execute_roles()
            csv,summary=paths['client']/'samples.csv.gz',paths['client']/'samples.csv.open.json'
            activity=evidence['server']['worker_activity']
        trial,_=load(csv,summary,collect_latencies=False)
        if 'accounting_cpu' in request and trial.get('accounting_cpu')!=request['accounting_cpu']:
            raise ValueError('Accounting CPU differs from qualification request')
        statistics=temporal_statistics(trial)
        result=dict(passed=True,paper_data=False,request=request,worker_activity=activity,
                    summary=str(summary.resolve()),csv=str(csv.resolve()),statistics=statistics)
        atomic(directory/'qualification.json',result)
        print(json.dumps(dict(stage='point',system=case['system'],workers=case['workers'],rate=rate,
            duration_ms=duration_ms,seed=seed,**statistics['whole'])),flush=True)
        return result

    def run_case(self, case):
        points=[];low=0;high=None
        for rate in case['pilot_rates']:
            point=self.acquire(case,rate,10000,self.plan['pilot_seed']);points.append(point)
            if point['statistics']['whole']['loss']>.01:high=rate;break
            low=rate
        if not low or high is None:raise ValueError('Pilot did not bracket a positive qualifying boundary')
        for _ in range(self.plan['refinement_points']):
            rate=((low+high)//2//1000)*1000
            if rate<=low or rate>=high:break
            point=self.acquire(case,rate,10000,self.plan['pilot_seed']);points.append(point)
            if point['statistics']['whole']['loss']>.01:high=rate
            else:low=rate
        # Preserve every point. Select from the contiguous qualifying prefix,
        # including any non-monotonic pilot/refinement observations.
        ordered=sorted(points,key=lambda p:p['request']['rate'])
        qualifying=[]
        for point in ordered:
            if point['statistics']['whole']['loss']>.01:break
            qualifying.append(point)
        if not qualifying:raise ValueError('No qualifying pilot point')
        candidate=qualifying[-1]['request']['rate']
        key=(case['system'],case['payload_bytes'],case['workers'])
        validate_activity_coverage({key:[p['worker_activity'] for p in points]})
        sustained=[self.acquire(case,candidate,60000,seed) for seed in self.plan['seeds']]
        result=dict(case=case,paper_data=False,pilot_bracket=[low,high],selected_rate=candidate,
                    pilots=points,sustained=sustained)
        atomic(self.root/f'{case["system"]}-{case["payload_bytes"]}-{case["workers"]}.json',result)
        return result

    def run(self):
        results=[]
        try:
            for case in self.plan['cases']:results.append(self.run_case(case))
        except BaseException as error:
            atomic(self.root/'result.json',dict(passed=False,error=str(error),completed=results,paper_data=False))
            raise
        atomic(self.root/'result.json',dict(passed=True,cases=results,paper_data=False))
        return results


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--hardware',type=Path,required=True)
    parser.add_argument('--plan',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    hardware=json.loads(args.hardware.read_text());plan=json.loads(args.plan.read_text())
    if (plan.get('scope')!='loaded-duration-qualification' or not plan.get('seeds') or
            not 1<=plan.get('refinement_points',0)<=6 or not 1<=plan.get('warmup_ms',0)<=10000):
        raise ValueError('Invalid bounded qualification plan')
    if (not 1<=len(plan['seeds'])<=5 or len(set(plan['seeds']))!=len(plan['seeds'])
            or any(type(s) is not int or not 1<=s<=0xffffffff for s in [plan.get('pilot_seed'),*plan['seeds']])
            or type(plan.get('slot_ns')) is not int or not 1<=plan['slot_ns']<=1000000
            or type(plan.get('max_send_lag_ns')) is not int or not 1<=plan['max_send_lag_ns']<=1000000):
        raise ValueError('Invalid qualification seeds/arrival configuration')
    if len(plan.get('cases',[]))>6 or not plan.get('cases'):raise ValueError('Qualification requires one through six cases')
    for case in plan['cases']:
        if (case['system'] not in ('LH','DPDK','CS-A64') or case['payload_bytes'] not in (8,128,1432)
                or case['workers'] not in (1,2,3,4) or case['pilot_rates']!=sorted(set(case['pilot_rates']))
                or not 1<=len(case['pilot_rates'])<=12
                or any(type(r) is not int or not 1<=r<=100000000 for r in case['pilot_rates'])):raise ValueError('Invalid qualification case')
    # Share the campaign hardware lock, even though this foreground diagnostic
    # never publishes cohorts or launches the unattended controller.
    lock_path=Path(__file__).resolve().parents[3]/'out/echo-campaign.lock'
    lock_path.parent.mkdir(exist_ok=True)
    with lock_path.open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        Qualification(args.output,hardware,plan).run()


if __name__=='__main__':main()
