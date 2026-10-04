# Only the first stage of each single-bit synchronizer crosses clock domains.
# Keep timing between the two ASYNC_REG stages normally constrained.
set gsync_cdc_meta [get_cells -quiet -hier -filter {NAME =~ *gsync_release_monitor/req_meta_reg || NAME =~ *gsync_release_monitor/ack_meta_reg}]
if {[llength $gsync_cdc_meta] != 2} {
    error "Expected both GSYNC release CDC first-stage registers"
}
set_false_path -to [get_pins -of_objects $gsync_cdc_meta -filter {REF_PIN_NAME == D}]
