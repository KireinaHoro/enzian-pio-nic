#include <assert.h>
#include <stdio.h>
#include "open_loop.h"
int main(void) {
    /* Eight independent partitions cover exactly the aggregate schedule. */
    for(unsigned core=0;core<8;core++) for(unsigned i=0;i<1000;i++) {
        unsigned slot=core+8*i;
        uint64_t due=ol_due(1000000,slot,3000000);
        assert(due==1000000+(uint64_t)slot*1000000000/3000000);
        assert(!ol_missed(due,1000000,slot,3000000,8));
        assert(ol_missed(ol_due(1000000,slot+8,3000000),1000000,slot,3000000,8));
    }
    assert(ol_due(0,180000000,3000000)==60000000000ULL);
    assert(ol_reply(OL_PENDING,100,10000100,10000000,true)==OL_ONTIME);
    assert(ol_reply(OL_PENDING,100,10000101,10000000,true)==OL_LATE);
    /* RX timestamp wins over delayed owner processing/expiration. */
    assert(ol_reply(OL_EXPIRED,100,200,10000000,true)==OL_ONTIME);
    assert(ol_reply(OL_ONTIME,100,200,10000000,true)==OL_ONTIME);
    assert(ol_reply(OL_LATE,100,200,10000000,true)==OL_LATE);
    assert(ol_reply(OL_ONTIME,100,200,10000000,false)==OL_MALFORMED);
    assert(ol_reply(OL_PENDING,100,99,10000000,true)==OL_MALFORMED);
    for(unsigned cores=1;cores<=32;cores++) for(unsigned batch=1;batch<=64;batch*=2)
        for(unsigned count=1;count<3000;count+=137) for(unsigned owner=0;owner<cores;owner++) {
            uint64_t previous=0;
            for(unsigned slot=owner;slot<count;) {
                unsigned n=ol_batch_count(slot,cores,batch,count);
                uint64_t due=ol_batch_due(0,slot,1000,cores,batch,count);
                assert(count*1000000ULL-due >= ol_batch_period(1000,cores,batch,count)/2);
                assert(n && n<=batch && due<count*1000000ULL && due>=previous);
                for(unsigned i=0;i<n;i++)
                    assert(ol_batch_due(0,slot+i*cores,1000,cores,batch,count)==due);
                previous=due;slot+=n*cores;
            }
        }
    puts("absolute scheduling and deadline boundaries: passed");
}
