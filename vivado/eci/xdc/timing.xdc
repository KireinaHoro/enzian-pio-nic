# false path for asynchronous reset in each SLR for DCS
set_false_path -through [get_pins -filter {REF_PIN_NAME=~reset_async} -of_objects [get_cells -hierarchical -filter {NAME=~i_app/dcs_odd || NAME=~i_app/dcs_even}]]
