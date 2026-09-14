# Artifact-only entry point: no Mill, Nix store, Git checkout or downloads.
# vivado -mode batch -source BUNDLE/vivado/eci/ci_build.tcl -tclargs BUNDLE NEW_OUTPUT ?project-only?
if {[catch {
    if {$argc < 2 || $argc > 3} {error "usage: BUNDLE NEW_OUTPUT ?project-only?"}
    set ci_project_only [expr {$argc == 3}]
    if {$ci_project_only && [lindex $argv 2] ne "project-only"} {error "unknown build mode"}
    if {[version -short] ne "2025.1"} {error "This input bundle requires Vivado 2025.1"}
    if {![string match {*SW Build 6140274*} [version]]} {error "Unexpected Vivado software build: [version]"}
    set ci_bundle [file normalize [lindex $argv 0]]
    set ci_output [file normalize [lindex $argv 1]]
    if {[file exists $ci_output]} {error "output directory already exists: $ci_output"}
    foreach name {NicEngine.v NicEngine_ips.sv NicEngine.xdc lauberhorn_trace_dma.v lauberhorn_trace_dma.xdc config.h regblock_bases.h lauberhorn_trace_dma_map.json} {
        if {![file isfile "$ci_bundle/generated/$name"]} {error "missing generated input: $name"}
    }
    if {![file isfile "$ci_bundle/static-shell/static_shell_routed.dcp"]} {error "missing static shell"}
    file mkdir $ci_output
    cd $ci_output
    set ci_rtls [list "$ci_bundle/generated/NicEngine.v" "$ci_bundle/generated/NicEngine_ips.sv" "$ci_bundle/generated/lauberhorn_trace_dma.v"]
    set ci_xdcs [list "$ci_bundle/generated/NicEngine.xdc" "$ci_bundle/generated/lauberhorn_trace_dma.xdc"]
    set argv [list --origin_dir "$ci_bundle/vivado/eci" --gen_rtls [join $ci_rtls :] --gen_xdcs [join $ci_xdcs :]]
    set argc [llength $argv]
    source "$ci_bundle/vivado/eci/create_project.tcl"
    if {$ci_project_only} {
        puts "VIVADO_PROJECT_READY $ci_output"
        exit 0
    }
    set argv {}
    set argc 0
    set env(ENZIAN_SHELL_DIR) "$ci_bundle/static-shell"
    source "$ci_bundle/vivado/eci/create_bitstream.tcl"
    foreach suffix {bit ltx routed.dcp} {
        set name "shell_lauberhorn-eci.$suffix"
        if {$suffix eq "routed.dcp"} {set name "shell_lauberhorn-eci_routed.dcp"}
        if {![file isfile "$ci_output/$name"]} {error "Vivado did not produce $name"}
    }
    puts "VIVADO_BUILD_COMPLETE $ci_output"
} ci_error ci_options]} {
    puts stderr "VIVADO_BUILD_FAILED: $ci_error"
    puts stderr [dict get $ci_options -errorinfo]
    exit 1
}
exit 0
