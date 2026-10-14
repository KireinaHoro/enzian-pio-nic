#!/usr/bin/env python3
"""Prepare a data-VF-only test driver from the exact Ubuntu kernel source."""
from pathlib import Path
import sys
import shutil
kernel,out=map(Path,sys.argv[1:])
out.mkdir()
source=kernel/'drivers/net/ethernet/cavium'
for name in ('thunder','common'):
 shutil.copytree(source/name,out/name)
for p in out.rglob('*'):
 p.chmod(0o755 if p.is_dir() else 0o644)
# Namespace the reused PTP API so stock nicvf and cavium_ptp remain loaded.
for p in out.rglob('*'):
 if p.suffix in ('.c','.h'):
  p.write_text(p.read_text().replace('cavium_ptp_', 'lh_ptp_'))
p=out/'common/cavium_ptp.c';s=p.read_text()
s=s.replace('#include <linux/pci.h>', '#include <linux/pci.h>\n#include <linux/platform_device.h>')
a=s.index('struct cavium_ptp *lh_ptp_get(void)');b=s.index('/**',a)
s=s[:a]+'''static struct cavium_ptp *test_clock;
static struct platform_device *test_device;
static u64 saved_cfg, saved_comp;
struct cavium_ptp *lh_ptp_get(void) { return test_clock ?: ERR_PTR(-ENODEV); }
void lh_ptp_put(struct cavium_ptp *ptp) { }

'''+s[b:]
a=s.index('static int lh_ptp_probe(')
s=s[:a]+'''int lh_ptp_init(void);
void lh_ptp_exit(void);

int lh_ptp_init(void)
{
    struct cavium_ptp *clock;
    struct resource res = { .start = 0x807000000000ULL,
        .end = 0x807000000fffULL, .flags = IORESOURCE_MEM };
    int err;
    test_device = platform_device_register_simple("lh-thx-ptp", -1, &res, 1);
    if (IS_ERR(test_device)) return PTR_ERR(test_device);
    clock = devm_kzalloc(&test_device->dev, sizeof(*clock), GFP_KERNEL);
    if (!clock) { err = -ENOMEM; goto fail; }
    clock->reg_base = devm_ioremap_resource(&test_device->dev, &res);
    if (IS_ERR(clock->reg_base)) { err = PTR_ERR(clock->reg_base); goto fail; }
    saved_cfg = readq(clock->reg_base + PTP_CLOCK_CFG);
    saved_comp = readq(clock->reg_base + PTP_CLOCK_COMP);
    if (saved_cfg & PTP_CLOCK_CFG_PTP_EN) { err = -EBUSY; goto fail; }
    spin_lock_init(&clock->spin_lock);
    clock->clock_rate = ptp_cavium_clock_get();
    if (!clock->clock_rate) { err = -EINVAL; goto fail; }
    clock->cycle_counter.read = lh_ptp_cc_read;
    clock->cycle_counter.mask = CYCLECOUNTER_MASK(64);
    clock->cycle_counter.mult = 1;
    clock->cycle_counter.shift = 0;
    clock->ptp_info = (struct ptp_clock_info) {
        .owner = THIS_MODULE, .name = "THX test PTP", .max_adj = 1000000000,
        .adjfine = lh_ptp_adjfine, .adjtime = lh_ptp_adjtime,
        .gettime64 = lh_ptp_gettime, .settime64 = lh_ptp_settime,
        .enable = lh_ptp_enable,
    };
    writeq(saved_cfg | PTP_CLOCK_CFG_PTP_EN, clock->reg_base + PTP_CLOCK_CFG);
    writeq(((u64)1000000000ULL << 32) / clock->clock_rate,
           clock->reg_base + PTP_CLOCK_COMP);
    timecounter_init(&clock->time_counter, &clock->cycle_counter, ktime_get_real_ns());
    clock->ptp_clock = ptp_clock_register(&clock->ptp_info, &test_device->dev);
    if (IS_ERR(clock->ptp_clock)) {
        err = PTR_ERR(clock->ptp_clock);
        writeq(saved_cfg, clock->reg_base + PTP_CLOCK_CFG);
        writeq(saved_comp, clock->reg_base + PTP_CLOCK_COMP);
        goto fail;
    }
    test_clock = clock;
    return 0;
fail:
    platform_device_unregister(test_device);
    return err;
}

void lh_ptp_exit(void)
{
    struct cavium_ptp *clock = test_clock;
    test_clock = NULL;
    ptp_clock_unregister(clock->ptp_clock);
    writeq(saved_cfg, clock->reg_base + PTP_CLOCK_CFG);
    writeq(saved_comp, clock->reg_base + PTP_CLOCK_COMP);
    platform_device_unregister(test_device);
}
'''
p.write_text(s)
p=out/'thunder/nicvf_main.c';s=p.read_text().replace('#define DRV_NAME\t"nicvf"','#define DRV_NAME\t"lh_nicvf"')
s=s.replace('static int nicvf_probe(struct pci_dev *pdev, const struct pci_device_id *ent)', 'static int nicvf_probe(struct pci_dev *pdev, const struct pci_device_id *ent)')
needle='\tptp_clock = lh_ptp_get();';assert s.count(needle)==1
s=s.replace(needle,'\tif (!test_bdf || strcmp(pci_name(pdev), test_bdf)) return -ENODEV;\n'+needle)
pos=s.index('static int nicvf_probe(')
s=s[:pos]+'''static char *test_bdf;
module_param(test_bdf, charp, 0400);
MODULE_PARM_DESC(test_bdf, "Explicit data VF PCI address; all other devices refused");
int lh_ptp_init(void);
void lh_ptp_exit(void);

'''+s[pos:]
s=s.replace('return pci_register_driver(&nicvf_driver);', '''int err;
    if (!test_bdf) return -EINVAL;
    err = lh_ptp_init();
    if (err) return err;
    err = pci_register_driver(&nicvf_driver);
    if (err) lh_ptp_exit();
    return err;''')
s=s.replace('pci_unregister_driver(&nicvf_driver);','pci_unregister_driver(&nicvf_driver);\n\tlh_ptp_exit();')
s=s.replace('nic = nic->pnicvf;\n\n\t/* Sync', 'nic = nic->pnicvf;\n\tif (netif_msg_tx_done(nic)) netdev_info(netdev, "PTP CQ status=%u raw=%llu pending=%d\\n", cqe_tx->send_status, cqe_tx->ptp_timestamp, !!nic->ptp_skb);\n\n\t/* Sync')
p.write_text(s)
# CN88xx selector: bit 2 is BGX block; bits 1:0 are LMAC. BGX1/LMAC0 = 4.
p=out/'thunder/nicvf_queues.c'
s=p.read_text();assert s.count('sq_cfg.tstmp_bgx_intf = 0;') == 1
s=s.replace('sq_cfg.tstmp_bgx_intf = 0;', 'sq_cfg.tstmp_bgx_intf = 4;')
s=s.replace('hdr->tstmp = 1;', 'hdr->tstmp = 1;\n\tif (netif_msg_tx_queued(nic)) netdev_info(nic->netdev, "PTP TX requested flags=%u\\n", skb_shinfo(skb)->tx_flags);')
s=s.replace('\tnicvf_sq_doorbell(nic, skb, sq_num, subdesc_cnt);', '\tif (netif_msg_tx_queued(nic) && (skb_shinfo(skb)->tx_flags & SKBTX_IN_PROGRESS)) {\n        struct sq_hdr_subdesc *h = (void *)GET_SQ_DESC(sq, hdr_sqe);\n        netdev_info(nic->netdev, "PTP HDR sq=%d w0=%016llx w1=%016llx TSTMP=%u DS=%u TSO=%u PNC=%u\\n",\n            sq_num, ((u64 *)h)[0], ((u64 *)h)[1], h->tstmp, h->dont_send, h->tso, h->post_cqe);\n    }\n\tnicvf_sq_doorbell(nic, skb, sq_num, subdesc_cnt);')
p.write_text(s)
(out/'Makefile').write_text('obj-m += lh_nicvf.o\nlh_nicvf-y := thunder/nicvf_main.o thunder/nicvf_queues.o thunder/nicvf_ethtool.o common/cavium_ptp.o\n')
