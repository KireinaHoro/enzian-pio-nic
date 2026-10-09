#!/usr/bin/env python3
"""Exercise the production AXI-Lite decoder against the old and fixed apertures."""
import pathlib
import re
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[2]
wrapper = root / 'vivado/eci/rtl/axil_regs_interconnect.v'
source = wrapper.read_text()
ports = re.findall(r'(input|output)\s+wire\s*(\[[^\]]+\])?\s+(\w+)', source)
width = int(re.search(r'M01_ADDR_WIDTH => (\d+)', (root / 'vivado/eci/rtl/lauberhorn_eci.vhd').read_text())[1])
with tempfile.TemporaryDirectory() as temp:
    temp = pathlib.Path(temp)
    for aperture in (11, width):
        decl = []
        for direction, size, name in ports:
            decl.append(f'{"reg" if direction == "input" else "wire"} {size} {name}' + (' = 0;' if direction == 'input' else ';'))
        tb = '''module tb;
localparam DATA_WIDTH=64, ADDR_WIDTH=44, STRB_WIDTH=8;
''' + '\n'.join(decl) + f'''
always #5 clk = ~clk;
axil_regs_interconnect #(.DATA_WIDTH(64), .ADDR_WIDTH(44),
.M00_BASE_ADDR(44'h0), .M00_ADDR_WIDTH(21),
.M01_BASE_ADDR(44'h200000), .M01_ADDR_WIDTH({aperture})) dut (
''' + ',\n'.join(f'.{n}({n})' for _, _, n in ports) + ''');
task check_read(input [43:0] addr, input integer target);
integer cycles;
begin
 @(negedge clk); s00_axil_araddr=addr; s00_axil_arvalid=1;
 do @(posedge clk); while (!s00_axil_arready);
 @(negedge clk); s00_axil_arvalid=0;
 cycles=0;
 while (!(m00_axil_arvalid || m01_axil_arvalid || s00_axil_rvalid) && cycles<30) begin
  @(negedge clk); cycles=cycles+1;
 end
 if (target==0 && !m00_axil_arvalid) $fatal(1,"NIC route missing");
 if (target==1 && (!m01_axil_arvalid || m01_axil_araddr!=addr)) $fatal(1,"CMAC route missing");
 if (target==2 && (!s00_axil_rvalid || s00_axil_rresp!=3)) $fatal(1,"Expected DECERR");
 if (target==0) begin
  m00_axil_arready=1; @(negedge clk); m00_axil_arready=0; m00_axil_rvalid=1;
  do @(posedge clk); while (!m00_axil_rready);
  @(negedge clk); m00_axil_rvalid=0;
 end
 if (target==1) begin
  m01_axil_arready=1; @(negedge clk); m01_axil_arready=0; m01_axil_rvalid=1;
  do @(posedge clk); while (!m01_axil_rready);
  @(negedge clk); m01_axil_rvalid=0;
 end
 wait(s00_axil_rvalid); s00_axil_rready=1;
 @(negedge clk); s00_axil_rready=0;
 repeat(3) @(negedge clk);
end
endtask
initial begin
 #100000; $fatal(1,"watchdog");
end
initial begin
 rst=1; repeat(4) @(negedge clk); rst=0;
 check_read('h24,0);
 check_read('h200024,1);
 check_read('h200204,1);
''' + f"check_read('h201000,{2 if aperture == 11 else 1});\ncheck_read('h20107c,{2 if aperture == 11 else 1});\n" + '''
 check_read('h210000,2);
 $display("PASS decoder aperture"); $finish;
end
endmodule
'''
        (temp / 'tb.v').write_text(tb)
        subprocess.run(['iverilog', '-g2012', '-s', 'tb', '-o', str(temp/'sim'), str(temp/'tb.v'), str(wrapper), *[str(root/'deps/blocks/deps/verilog-axi/rtl'/f) for f in ('axil_interconnect.v', 'arbiter.v', 'priority_encoder.v')]], check=True)
        subprocess.run(['vvp', str(temp/'sim')], check=True, timeout=10)
        print(f'PASS {aperture}-bit aperture (historical negative control)' if aperture == 11 else f'PASS {aperture}-bit production aperture')
