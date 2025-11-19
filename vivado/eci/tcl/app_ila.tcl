# Configure the application ILA.
#
# When $PWD is the project root, program the FPGA and then call with:
#
#       source vivado/eci/tcl/app_ila.tcl

# ============ Helper functions ============

proc def_alloc_chan_with_addr { busName probeNum } {
    variable ila
    create_hw_probe -no_gui_update -map probe$probeNum[41]       alloc_$busName.valid      $ila
    create_hw_probe -no_gui_update -map probe$probeNum[40]       alloc_$busName.ready      $ila
    create_hw_probe -no_gui_update -map probe$probeNum[39:16]    alloc_$busName.addr[23:0] $ila
    create_hw_probe -no_gui_update -map probe$probeNum[15:0]     alloc_$busName.size[15:0] $ila
}

proc def_alloc_chan_without_addr { busName probeNum } {
    variable ila
    create_hw_probe -no_gui_update -map probe$probeNum[17]       alloc_$busName.valid      $ila
    create_hw_probe -no_gui_update -map probe$probeNum[16]       alloc_$busName.ready      $ila
    create_hw_probe -no_gui_update -map probe$probeNum[15:0]     alloc_$busName.size[15:0] $ila
}

proc def_dma_wr_desc { probeNum } {
    variable ila
    create_hw_probe -no_gui_update -map probe$probeNum[36]      dma_write_desc.valid         $ila
    create_hw_probe -no_gui_update -map probe$probeNum[35]      dma_write_desc.ready         $ila
    create_hw_probe -no_gui_update -map probe$probeNum[34:16]   dma_write_desc.addr[18:0]    $ila
    create_hw_probe -no_gui_update -map probe$probeNum[15:0]    dma_write_desc.len[15:0]     $ila
}

proc def_dma_wr_status { probeNum } {
    variable ila
    create_hw_probe -no_gui_update -map probe$probeNum[16]      dma_write_status.valid         $ila
    create_hw_probe -no_gui_update -map probe$probeNum[15:0]    dma_write_status.len[15:0]     $ila
}

proc states_to_enum_defs { stateList } {
    set idx 0
    set numStates [llength $stateList]
    set width [string length [format "%b" [expr $numStates - 1]]]
    foreach s $stateList {
        lappend ret $s
        lappend ret [format "eq%d'h%x" $width $idx]
        incr idx
    }
    return $ret
}

proc def_core_states { coreNum probeNum } {
    variable ila

    add_hw_probe_enum -dict [states_to_enum_defs {
        BOOT idle decodeAr waitDesc waitInv
        sendDesc readPktBuf sendDummyData sendPktData
    }] [create_hw_probe -no_gui_update -map probe$probeNum[3:0]      core${coreNum}_rxRouter_state[3:0]       $ila]

    add_hw_probe_enum -dict [states_to_enum_defs {
        BOOT idle decodeCmd waitInv sendDesc
        readPktBuf sendPktData transmitDesc
    }] [create_hw_probe -no_gui_update -map probe$probeNum[6:4]      core${coreNum}_txRouter_read_state[2:0]  $ila]

    add_hw_probe_enum -dict [states_to_enum_defs {
        BOOT idle decodeCmd saveDesc
        writePktBufCmd writePktBufData writePktBufResp
    }] [create_hw_probe -no_gui_update -map probe$probeNum[9:7]      core${coreNum}_txRouter_write_state[2:0] $ila]

    add_hw_probe_enum -dict [states_to_enum_defs {
        BOOT waitHostRead hostIssuedRead repeatPacket
        invalidatePacketData invalidateCtrl waitInvResp
    }] [create_hw_probe -no_gui_update -map probe$probeNum[12:10]    core${coreNum}_rxFsm_state[2:0]          $ila]

    add_hw_probe_enum -dict [states_to_enum_defs {
        BOOT idle waitPacket invalidateCtrl
        waitInvResp invalidatePacketData tx
    }] [create_hw_probe -no_gui_update -map probe$probeNum[15:13]    core${coreNum}_txFsm_state[2:0]          $ila]

    create_hw_probe -no_gui_update -map probe$probeNum[16]       core${coreNum}_rxClIdx                   $ila
    create_hw_probe -no_gui_update -map probe$probeNum[17]       core${coreNum}_txClIdx                   $ila
}

proc def_dma_state { probeNum } {
    variable ila

    add_hw_probe_enum -dict [states_to_enum_defs {
        BOOT idle sendDmaCmd waitDma
        enqueuePkt
    }] [create_hw_probe -no_gui_update -map probe$probeNum[2:0]    dma_rxFsm_state[2:0]          $ila]
}

proc def_dcs_trace { sliceName unitNum probeNum } {
    variable ila
    create_hw_probe -no_gui_update -map probe$probeNum[57]       dcs_${sliceName}_trace_${unitNum}.valid       $ila
    create_hw_probe -no_gui_update -map probe$probeNum[56]       dcs_${sliceName}_trace_${unitNum}.error       $ila
    create_hw_probe -no_gui_update -map probe$probeNum[55:49]    dcs_${sliceName}_trace_${unitNum}.state[6:0]  $ila
    create_hw_probe -no_gui_update -map probe$probeNum[48:45]    dcs_${sliceName}_trace_${unitNum}.action[3:0] $ila
    create_hw_probe -no_gui_update -map probe$probeNum[44:40]    dcs_${sliceName}_trace_${unitNum}.req[4:0]    $ila
    create_hw_probe -no_gui_update -map probe$probeNum[39:0]     dcs_${sliceName}_trace_${unitNum}.cli[39:0]   $ila
}

proc def_dcs_lcl { sliceName busName probeNum } {
    variable ila
    create_hw_probe -no_gui_update -map probe$probeNum[75]       ${busName}_${sliceName}.valid      $ila
    create_hw_probe -no_gui_update -map probe$probeNum[74]       ${busName}_${sliceName}.ready      $ila
    create_hw_probe -no_gui_update -map probe$probeNum[39:0]     ${busName}_${sliceName}.addr[39:0] $ila
}

proc dcs_slice_to_slot { sliceName } {
    if {$sliceName == "even"} {
        return 0
    } {
        return 1
    }
}

proc add_group_with_highlight { groupName signalList highlightList groupParent memberRegexp } {
    if {$groupParent == ""} {
        set groupParent [current_wave_config]
    }

    set group [add_wave_group -into $groupParent $groupName]
    foreach s $signalList {
        set mycolor Lime
        foreach h $highlightList {
            if {[regexp "$h$" $s]} {
                set mycolor Red
                break
            }
        }

        regexp $memberRegexp $s match dispName

        add_wave -into $group -color $mycolor -name $dispName $s
    }
}

proc add_stream { groupName signals {memberRegexp "(.*)"} {groupParent ""} } {
    add_group_with_highlight $groupName $signals {ready valid} $groupParent $memberRegexp
}

proc add_group { groupName signals {memberRegexp "(.*)"} {groupParent ""} } {
    add_group_with_highlight $groupName $signals {} $groupParent $memberRegexp
}

proc add_dcs_axi { sliceName } {
    variable ila

    set slotNum [dcs_slice_to_slot $sliceName]

    set axi_group    [add_wave_group "DCS $sliceName AXI"]

    foreach ch {aw w b ar r} {
        add_stream "[string toupper $ch]" [get_hw_probes -of_objects $ila -filter "NAME.SHORT =~ net_slot_${slotNum}_axi_${ch}*"] {_([a-z]*)$} $axi_group
    }
}

proc trigger_dcs_axi_read { sliceName } {
    variable ila
    set slotNum [dcs_slice_to_slot $sliceName]

    set_property TRIGGER_COMPARE_VALUE eq1'b1 [get_hw_probes -of_object $ila -filter "NAME.SHORT == net_slot_${slotNum}_axi_arvalid"]
    set_property TRIGGER_COMPARE_VALUE eq1'b1 [get_hw_probes -of_object $ila -filter "NAME.SHORT == net_slot_${slotNum}_axi_rvalid"]
}

proc trigger_dcs_axi_write { sliceName } {
    variable ila
    set slotNum [dcs_slice_to_slot $sliceName]

    set_property TRIGGER_COMPARE_VALUE eq1'b1 [get_hw_probes -of_object $ila -filter "NAME.SHORT == net_slot_${slotNum}_axi_awvalid"]
    set_property TRIGGER_COMPARE_VALUE eq1'b1 [get_hw_probes -of_object $ila -filter "NAME.SHORT == net_slot_${slotNum}_axi_wvalid"]
    set_property TRIGGER_COMPARE_VALUE eq1'b1 [get_hw_probes -of_object $ila -filter "NAME.SHORT == net_slot_${slotNum}_axi_bvalid"]
}

# ============ Configuration ============

# Find our ILA instance.
set mydev [lindex [get_hw_devices] 0]
set ila [get_hw_ilas -of_objects $mydev -filter {CELL_NAME =~ "i_app/design_1_i/hier_ilas/ila_app/*"}]
regexp {(\d+)$} [get_property NAME $ila] match ila_idx
current_wave_config hw_ila_data_${ila_idx}.wcfg

# Delete all user-defined probes first to avoid conflict.
set usr_probes [get_hw_probes -quiet -of_object $ila -filter {SOURCE == user}]
if {$usr_probes != ""} {
    delete_hw_probe $usr_probes
}

# Reset waveform window.
set all_waves [get_waves *]
if {$all_waves != ""} {
    remove_wave $all_waves
}

# Create all user-defined probes.
def_core_states 0 0
def_core_states 1 1
def_core_states 2 2
def_core_states 3 3
def_core_states 4 4

def_dcs_lcl even lci  5
def_dcs_lcl even lcia 6
def_dcs_lcl even ul   7
def_dcs_lcl odd  lci  8
def_dcs_lcl odd  lcia 9
def_dcs_lcl odd  ul   10

def_dcs_trace even 0 11
def_dcs_trace even 1 12
def_dcs_trace odd  0 13
def_dcs_trace odd  1 14

def_alloc_chan_with_addr    free 16
def_alloc_chan_with_addr    resp 17
def_alloc_chan_without_addr req  18

def_dma_state 19
def_dma_wr_desc 20
def_dma_wr_status 21

# Trigger once to populate all waves (or add_wave might fail).
run_hw_ila -trigger_now $ila
wait_on_hw_ila $ila
display_hw_ila_data [upload_hw_ila_data $ila]

# Add useful waves.
# No need to filter with {SOURCE == user}, netlist probes have the net name as CUSTOM
add_group "Bypass Core States" [get_hw_probes -of_objects $ila -regexp core0_.*] {core0_(.*)$}

add_dcs_axi even
add_dcs_axi odd

set groupItemNameRe {\.([a-z]*)$}

add_wave -name "200 MHz Clock" [get_hw_probes -of_objects $ila -regexp .*probe15]

add_stream "Alloc Request" [get_hw_probes -of_objects $ila -regexp alloc_req.*] $groupItemNameRe
add_stream "Alloc Response" [get_hw_probes -of_objects $ila -regexp alloc_resp.*] $groupItemNameRe
add_stream "Alloc Free Request" [get_hw_probes -of_objects $ila -regexp alloc_free.*] $groupItemNameRe

add_stream "DMA Write Desc" [get_hw_probes -of_objects $ila -regexp dma_write_desc.*] $groupItemNameRe
add_stream "DMA Write Status" [get_hw_probes -of_objects $ila -regexp dma_write_status.*] $groupItemNameRe
add_wave -name "DMA RX State" [get_hw_probes -of_objects $ila -regexp dma_rxFsm_state]

# Configure ILA trigger and window.
set total_samples [get_property STATIC.MAX_DATA_DEPTH $ila]
set window_depth 64
set_property CONTROL.DATA_DEPTH $window_depth $ila
set_property CONTROL.WINDOW_COUNT [expr $total_samples / $window_depth] $ila
set_property CONTROL.TRIGGER_POSITION 2 $ila
set_property CONTROL.TRIGGER_CONDITION OR $ila

# Trigger on DCS AXI transactions.
trigger_dcs_axi_read even
trigger_dcs_axi_read odd
# trigger_dcs_axi_write even
# trigger_dcs_axi_write odd

# Arm the ILA.
run_hw_ila $ila
