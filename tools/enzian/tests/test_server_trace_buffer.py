"""Exercise the real C writer with no NIC/kernel dependency."""
import csv
import json
import os
import shlex
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
HARNESS = r'''
#include "server_trace.h"
#include "fiber/scheduler.h"
#include <pthread.h>
#include <signal.h>
#include <stdlib.h>
#include <unistd.h>
#include <assert.h>
__thread struct fiber *current_fiber, *main_fiber;
static void *produce(void *arg) {
    server_trace_thread_init();
    for (int i=0;i<3;i++) {
        struct server_trace_row row;
        server_trace_rx_begin(&row,(int)(long)arg);
        row.request_id=i; row.id_valid=true;
        server_trace_write(&row);
        server_trace_write(&row); /* duplicate terminal call must do nothing */
    }
    return NULL;
}
int main(int argc, char **argv) {
    (void)argc;
    server_trace_init();
    pthread_t threads[2];
    for(long i=0;i<2;i++) assert(!pthread_create(&threads[i],NULL,produce,(void*)i));
    for(int i=0;i<2;i++) pthread_join(threads[i],NULL);
    assert(access(argv[1],F_OK)); /* no output while collecting */
    kill(getpid(),SIGUSR2);
    for(int i=0;i<1000 && access(argv[2],F_OK);i++) usleep(1000);
    assert(!access(argv[2],F_OK));
    server_trace_fini();
    return 0;
}
'''

class BufferedTraceTests(unittest.TestCase):
    def test_export_and_overflow(self):
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp); (d/'main.c').write_text(HARNESS)
            subprocess.run([os.environ.get('CC','cc'),'-std=gnu11','-Wall','-Wextra','-Werror',
                            '-pthread',*shlex.split(subprocess.check_output(['pkg-config','--cflags','libtirpc'],text=True)),
                            '-I'+str(ROOT/'sw/include'),
                            '-I'+str(ROOT/'sw/rt'),str(d/'main.c'),
                            str(ROOT/'sw/rt/server_trace.c'),'-o',str(d/'test')],check=True)
            for capacity, count, dropped in [(4,6,0),(2,4,2)]:
                path=d/f'{capacity}.csv'; status=Path(str(path)+'.status.json')
                env=dict(os.environ,LAUBERHORN_SERVER_TRACE_CSV=str(path),
                         LAUBERHORN_TRACE_CAPACITY=str(capacity))
                subprocess.run([str(d/'test'),str(path),str(status)],env=env,check=True,timeout=5)
                meta=json.loads(status.read_text())
                self.assertEqual((meta['rows'],meta['dropped']),(count,dropped))
                self.assertEqual(meta['loss_free'],not dropped)
                with path.open() as f: rows=list(csv.DictReader(f))
                self.assertEqual(len(rows),count)
                self.assertTrue(all(None not in row and None not in row.values() for row in rows))
                self.assertEqual({r['worker_id'] for r in rows},{'0','1'})

if __name__=='__main__': unittest.main()
