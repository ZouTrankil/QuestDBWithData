CREATE TABLE 'backtest_daily' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT
) timestamp(trade_date) PARTITION BY DAY
DEDUP UPSERT KEYS(trade_date,ts_code);

CREATE TABLE 'backtest_daily_cache' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'backtest_daily_cache_backup_20260928_200527_759489' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'backtest_daily_cache_coverage' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'backtest_daily_cache_coverage_backup_20260928_200527_882446' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'balance' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	f_ann_date STRING,
	end_date STRING,
	report_type STRING,
	comp_type STRING,
	end_type STRING,
	total_share DOUBLE,
	cap_rese DOUBLE,
	undistr_porfit DOUBLE,
	surplus_rese DOUBLE,
	special_rese DOUBLE,
	money_cap DOUBLE,
	trad_asset DOUBLE,
	notes_receiv DOUBLE,
	accounts_receiv DOUBLE,
	oth_receiv DOUBLE,
	prepayment DOUBLE,
	div_receiv DOUBLE,
	int_receiv DOUBLE,
	inventories DOUBLE,
	amor_exp DOUBLE,
	nca_within_1y DOUBLE,
	sett_rsrv DOUBLE,
	loanto_oth_bank_fi DOUBLE,
	premium_receiv DOUBLE,
	reinsur_receiv DOUBLE,
	reinsur_res_receiv DOUBLE,
	pur_resale_fa DOUBLE,
	oth_cur_assets DOUBLE,
	total_cur_assets DOUBLE,
	fa_avail_for_sale DOUBLE,
	htm_invest DOUBLE,
	lt_eqt_invest DOUBLE,
	invest_real_estate DOUBLE,
	time_deposits DOUBLE,
	oth_assets DOUBLE,
	lt_rec DOUBLE,
	fix_assets DOUBLE,
	cip DOUBLE,
	const_materials DOUBLE,
	fixed_assets_disp DOUBLE,
	produc_bio_assets DOUBLE,
	oil_and_gas_assets DOUBLE,
	intan_assets DOUBLE,
	r_and_d DOUBLE,
	goodwill DOUBLE,
	lt_amor_exp DOUBLE,
	defer_tax_assets DOUBLE,
	decr_in_disbur DOUBLE,
	oth_nca DOUBLE,
	total_nca DOUBLE,
	cash_reser_cb DOUBLE,
	depos_in_oth_bfi DOUBLE,
	prec_metals DOUBLE,
	deriv_assets DOUBLE,
	rr_reins_une_prem DOUBLE,
	rr_reins_outstd_cla DOUBLE,
	rr_reins_lins_liab DOUBLE,
	rr_reins_lthins_liab DOUBLE,
	refund_depos DOUBLE,
	ph_pledge_loans DOUBLE,
	refund_cap_depos DOUBLE,
	indep_acct_assets DOUBLE,
	client_depos DOUBLE,
	client_prov DOUBLE,
	transac_seat_fee DOUBLE,
	invest_as_receiv DOUBLE,
	total_assets DOUBLE,
	lt_borr DOUBLE,
	st_borr DOUBLE,
	cb_borr DOUBLE,
	depos_ib_deposits DOUBLE,
	loan_oth_bank DOUBLE,
	trading_fl DOUBLE,
	notes_payable DOUBLE,
	acct_payable DOUBLE,
	adv_receipts DOUBLE,
	sold_for_repur_fa DOUBLE,
	comm_payable DOUBLE,
	payroll_payable DOUBLE,
	taxes_payable DOUBLE,
	int_payable DOUBLE,
	div_payable DOUBLE,
	oth_payable DOUBLE,
	acc_exp DOUBLE,
	deferred_inc DOUBLE,
	st_bonds_payable DOUBLE,
	payable_to_reinsurer DOUBLE,
	rsrv_insur_cont DOUBLE,
	acting_trading_sec DOUBLE,
	acting_uw_sec DOUBLE,
	non_cur_liab_due_1y DOUBLE,
	oth_cur_liab DOUBLE,
	total_cur_liab DOUBLE,
	bond_payable DOUBLE,
	lt_payable DOUBLE,
	specific_payables DOUBLE,
	estimated_liab DOUBLE,
	defer_tax_liab DOUBLE,
	defer_inc_non_cur_liab DOUBLE,
	oth_ncl DOUBLE,
	total_ncl DOUBLE,
	depos_oth_bfi DOUBLE,
	deriv_liab DOUBLE,
	depos DOUBLE,
	agency_bus_liab DOUBLE,
	oth_liab DOUBLE,
	prem_receiv_adva DOUBLE,
	depos_received DOUBLE,
	ph_invest DOUBLE,
	reser_une_prem DOUBLE,
	reser_outstd_claims DOUBLE,
	reser_lins_liab DOUBLE,
	reser_lthins_liab DOUBLE,
	indept_acc_liab DOUBLE,
	pledge_borr DOUBLE,
	indem_payable DOUBLE,
	policy_div_payable DOUBLE,
	total_liab DOUBLE,
	treasury_share DOUBLE,
	ordin_risk_reser DOUBLE,
	forex_differ DOUBLE,
	invest_loss_unconf DOUBLE,
	minority_int DOUBLE,
	total_hldr_eqy_exc_min_int DOUBLE,
	total_hldr_eqy_inc_min_int DOUBLE,
	total_liab_hldr_eqy DOUBLE,
	lt_payroll_payable DOUBLE,
	oth_comp_income DOUBLE,
	oth_eqt_tools DOUBLE,
	oth_eqt_tools_p_shr DOUBLE,
	lending_funds DOUBLE,
	acc_receivable DOUBLE,
	st_fin_payable DOUBLE,
	payables DOUBLE,
	hfs_assets DOUBLE,
	hfs_sales DOUBLE,
	cost_fin_assets DOUBLE,
	fair_value_fin_assets DOUBLE,
	cip_total DOUBLE,
	oth_pay_total DOUBLE,
	long_pay_total DOUBLE,
	debt_invest DOUBLE,
	oth_debt_invest DOUBLE,
	oth_eq_invest DOUBLE,
	oth_illiq_fin_assets DOUBLE,
	oth_eq_ppbond DOUBLE,
	receiv_financing DOUBLE,
	use_right_assets DOUBLE,
	lease_liab DOUBLE,
	contract_assets DOUBLE,
	contract_liab DOUBLE,
	accounts_receiv_bill DOUBLE,
	accounts_pay DOUBLE,
	oth_rcv_total DOUBLE,
	fix_assets_total DOUBLE,
	update_flag STRING,
	update_time TIMESTAMP
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date);

CREATE TABLE 'block_trade' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	price DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	buyer STRING,
	seller STRING
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date,price,vol,buyer,seller);

CREATE TABLE 'block_trade_backup_20260928_181026_670250' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	price DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	buyer STRING,
	seller STRING
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date,price,vol,buyer,seller);

CREATE TABLE 'broker_recommend' ( 
	month TIMESTAMP,
	broker SYMBOL,
	ts_code SYMBOL,
	name STRING
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month,ts_code);

CREATE TABLE 'broker_statement_cashflows' ( 
	source_key SYMBOL,
	broker_name SYMBOL,
	account_id SYMBOL,
	client_no STRING,
	business_date STRING,
	event_time STRING,
	event_at TIMESTAMP,
	serial_no STRING,
	market SYMBOL,
	currency SYMBOL,
	transaction_type SYMBOL,
	amount DOUBLE,
	post_balance DOUBLE,
	remark STRING,
	source_page INT,
	source_file STRING,
	source_sha256 STRING,
	statement_period STRING,
	imported_at TIMESTAMP
) timestamp(event_at) PARTITION BY MONTH
DEDUP UPSERT KEYS(source_key,event_at);

CREATE TABLE 'broker_statement_deliveries' ( 
	source_key SYMBOL,
	broker_name SYMBOL,
	account_id SYMBOL,
	client_no STRING,
	business_date STRING,
	event_time STRING,
	event_at TIMESTAMP,
	serial_no STRING,
	security_code SYMBOL,
	ts_code SYMBOL,
	security_name STRING,
	business_flag SYMBOL,
	side SYMBOL,
	quantity DOUBLE,
	price DOUBLE,
	trade_amount DOUBLE,
	net_commission DOUBLE,
	stamp_duty DOUBLE,
	transfer_fee DOUBLE,
	regulatory_fee DOUBLE,
	handling_fee DOUBLE,
	other_fee DOUBLE,
	security_fee DOUBLE,
	clearing_amount DOUBLE,
	post_cash_balance DOUBLE,
	post_security_balance DOUBLE,
	currency SYMBOL,
	order_id STRING,
	accrued_interest DOUBLE,
	source_page INT,
	source_file STRING,
	source_sha256 STRING,
	statement_period STRING,
	imported_at TIMESTAMP,
	source_section SYMBOL
) timestamp(event_at) PARTITION BY MONTH
DEDUP UPSERT KEYS(source_key,event_at);

CREATE TABLE 'cashflow' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	f_ann_date STRING,
	end_date STRING,
	comp_type STRING,
	report_type STRING,
	end_type STRING,
	net_profit DOUBLE,
	finan_exp DOUBLE,
	c_fr_sale_sg DOUBLE,
	recp_tax_rends DOUBLE,
	n_depos_incr_fi DOUBLE,
	n_incr_loans_cb DOUBLE,
	n_inc_borr_oth_fi DOUBLE,
	prem_fr_orig_contr DOUBLE,
	n_incr_insured_dep DOUBLE,
	n_reinsur_prem DOUBLE,
	n_incr_disp_tfa DOUBLE,
	ifc_cash_incr DOUBLE,
	n_incr_disp_faas DOUBLE,
	n_incr_loans_oth_bank DOUBLE,
	n_cap_incr_repur DOUBLE,
	c_fr_oth_operate_a DOUBLE,
	c_inf_fr_operate_a DOUBLE,
	c_paid_goods_s DOUBLE,
	c_paid_to_for_empl DOUBLE,
	c_paid_for_taxes DOUBLE,
	n_incr_clt_loan_adv DOUBLE,
	n_incr_dep_cbob DOUBLE,
	c_pay_claims_orig_inco DOUBLE,
	pay_handling_chrg DOUBLE,
	pay_comm_insur_plcy DOUBLE,
	oth_cash_pay_oper_act DOUBLE,
	st_cash_out_act DOUBLE,
	n_cashflow_act DOUBLE,
	oth_recp_ral_inv_act DOUBLE,
	c_disp_withdrwl_invest DOUBLE,
	c_recp_return_invest DOUBLE,
	n_recp_disp_fiolta DOUBLE,
	n_recp_disp_sobu DOUBLE,
	stot_inflows_inv_act DOUBLE,
	c_pay_acq_const_fiolta DOUBLE,
	c_paid_invest DOUBLE,
	n_disp_subs_oth_biz DOUBLE,
	oth_pay_ral_inv_act DOUBLE,
	n_incr_pledge_loan DOUBLE,
	stot_out_inv_act DOUBLE,
	n_cashflow_inv_act DOUBLE,
	c_recp_borrow DOUBLE,
	proc_issue_bonds DOUBLE,
	oth_cash_recp_ral_fnc_act DOUBLE,
	stot_cash_in_fnc_act DOUBLE,
	free_cashflow DOUBLE,
	c_prepay_amt_borr DOUBLE,
	c_pay_dist_dpcp_int_exp DOUBLE,
	incl_dvd_profit_paid_sc_ms DOUBLE,
	oth_cashpay_ral_fnc_act DOUBLE,
	stot_cashout_fnc_act DOUBLE,
	n_cash_flows_fnc_act DOUBLE,
	eff_fx_flu_cash DOUBLE,
	n_incr_cash_cash_equ DOUBLE,
	c_cash_equ_beg_period DOUBLE,
	c_cash_equ_end_period DOUBLE,
	c_recp_cap_contrib DOUBLE,
	incl_cash_rec_saims DOUBLE,
	uncon_invest_loss DOUBLE,
	prov_depr_assets DOUBLE,
	depr_fa_coga_dpba DOUBLE,
	amort_intang_assets DOUBLE,
	lt_amort_deferred_exp DOUBLE,
	decr_deferred_exp DOUBLE,
	incr_acc_exp DOUBLE,
	loss_disp_fiolta DOUBLE,
	loss_scr_fa DOUBLE,
	loss_fv_chg DOUBLE,
	invest_loss DOUBLE,
	decr_def_inc_tax_assets DOUBLE,
	incr_def_inc_tax_liab DOUBLE,
	decr_inventories DOUBLE,
	decr_oper_payable DOUBLE,
	incr_oper_payable DOUBLE,
	others DOUBLE,
	im_net_cashflow_oper_act DOUBLE,
	conv_debt_into_cap DOUBLE,
	conv_copbonds_due_within_1y DOUBLE,
	fa_fnc_leases DOUBLE,
	im_n_incr_cash_equ DOUBLE,
	net_dism_capital_add DOUBLE,
	net_cash_rece_sec DOUBLE,
	credit_impa_loss DOUBLE,
	use_right_asset_dep DOUBLE,
	oth_loss_asset DOUBLE,
	end_bal_cash DOUBLE,
	beg_bal_cash DOUBLE,
	end_bal_cash_equ DOUBLE,
	beg_bal_cash_equ DOUBLE,
	update_flag STRING
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date);

CREATE TABLE 'cashflow_period_meta' ( 
	period SYMBOL,
	last_fetched_at STRING,
	row_count INT
);

CREATE TABLE 'cn_bond_yield_curve' ( 
	trade_date TIMESTAMP,
	curve_name SYMBOL,
	curve_code SYMBOL,
	tenor SYMBOL,
	yield_value DOUBLE,
	source SYMBOL
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(trade_date,curve_code,tenor);

CREATE TABLE 'cn_bond_yield_curve_backup_20260928_174124_549276' ( 
	trade_date TIMESTAMP,
	curve_name SYMBOL,
	curve_code SYMBOL,
	tenor SYMBOL,
	yield_value DOUBLE,
	source SYMBOL
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(trade_date,curve_code,tenor);

CREATE TABLE 'cn_cpi' ( 
	month TIMESTAMP,
	nt_val DOUBLE,
	nt_yoy DOUBLE,
	nt_mom DOUBLE,
	nt_accu DOUBLE,
	town_val DOUBLE,
	town_yoy DOUBLE,
	town_mom DOUBLE,
	town_accu DOUBLE,
	cnt_val DOUBLE,
	cnt_yoy DOUBLE,
	cnt_mom DOUBLE,
	cnt_accu DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'cn_cpi_backup_20260928_172457_114956' ( 
	month TIMESTAMP,
	nt_val DOUBLE,
	nt_yoy DOUBLE,
	nt_mom DOUBLE,
	nt_accu DOUBLE,
	town_val DOUBLE,
	town_yoy DOUBLE,
	town_mom DOUBLE,
	town_accu DOUBLE,
	cnt_val DOUBLE,
	cnt_yoy DOUBLE,
	cnt_mom DOUBLE,
	cnt_accu DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'cn_gdp' ( 
	quarter STRING,
	report_date TIMESTAMP,
	gdp DOUBLE,
	gdp_yoy DOUBLE,
	pi DOUBLE,
	pi_yoy DOUBLE,
	si DOUBLE,
	si_yoy DOUBLE,
	ti DOUBLE,
	ti_yoy DOUBLE
) timestamp(report_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(report_date);

CREATE TABLE 'cn_m' ( 
	month TIMESTAMP,
	m0 DOUBLE,
	m0_yoy DOUBLE,
	m0_mom DOUBLE,
	m1 DOUBLE,
	m1_yoy DOUBLE,
	m1_mom DOUBLE,
	m2 DOUBLE,
	m2_yoy DOUBLE,
	m2_mom DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'cn_m_backup_20260928_172511_058531' ( 
	month TIMESTAMP,
	m0 DOUBLE,
	m0_yoy DOUBLE,
	m0_mom DOUBLE,
	m1 DOUBLE,
	m1_yoy DOUBLE,
	m1_mom DOUBLE,
	m2 DOUBLE,
	m2_yoy DOUBLE,
	m2_mom DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'cn_pmi' ( 
	month TIMESTAMP,
	pmi010000 DOUBLE,
	pmi010100 DOUBLE,
	pmi010200 DOUBLE,
	pmi010300 DOUBLE,
	pmi010400 DOUBLE,
	pmi010401 DOUBLE,
	pmi010402 DOUBLE,
	pmi010403 DOUBLE,
	pmi010500 DOUBLE,
	pmi010501 DOUBLE,
	pmi010502 DOUBLE,
	pmi010503 DOUBLE,
	pmi010600 DOUBLE,
	pmi010601 DOUBLE,
	pmi010602 DOUBLE,
	pmi010603 DOUBLE,
	pmi010700 DOUBLE,
	pmi010701 DOUBLE,
	pmi010702 DOUBLE,
	pmi010703 DOUBLE,
	pmi010800 DOUBLE,
	pmi010801 DOUBLE,
	pmi010802 DOUBLE,
	pmi010803 DOUBLE,
	pmi010900 DOUBLE,
	pmi011000 DOUBLE,
	pmi011100 DOUBLE,
	pmi011200 DOUBLE,
	pmi011300 DOUBLE,
	pmi011400 DOUBLE,
	pmi011500 DOUBLE,
	pmi011600 DOUBLE,
	pmi011700 DOUBLE,
	pmi011800 DOUBLE,
	pmi011900 DOUBLE,
	pmi012000 DOUBLE,
	pmi020100 DOUBLE,
	pmi020101 DOUBLE,
	pmi020102 DOUBLE,
	pmi020200 DOUBLE,
	pmi020201 DOUBLE,
	pmi020202 DOUBLE,
	pmi020300 DOUBLE,
	pmi020301 DOUBLE,
	pmi020302 DOUBLE,
	pmi020400 DOUBLE,
	pmi020401 DOUBLE,
	pmi020402 DOUBLE,
	pmi020500 DOUBLE,
	pmi020501 DOUBLE,
	pmi020502 DOUBLE,
	pmi020600 DOUBLE,
	pmi020601 DOUBLE,
	pmi020602 DOUBLE,
	pmi020700 DOUBLE,
	pmi020800 DOUBLE,
	pmi020900 DOUBLE,
	pmi021000 DOUBLE,
	pmi030000 DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'cn_pmi_backup_20260928_172504_051432' ( 
	month TIMESTAMP,
	pmi010000 DOUBLE,
	pmi010100 DOUBLE,
	pmi010200 DOUBLE,
	pmi010300 DOUBLE,
	pmi010400 DOUBLE,
	pmi010401 DOUBLE,
	pmi010402 DOUBLE,
	pmi010403 DOUBLE,
	pmi010500 DOUBLE,
	pmi010501 DOUBLE,
	pmi010502 DOUBLE,
	pmi010503 DOUBLE,
	pmi010600 DOUBLE,
	pmi010601 DOUBLE,
	pmi010602 DOUBLE,
	pmi010603 DOUBLE,
	pmi010700 DOUBLE,
	pmi010701 DOUBLE,
	pmi010702 DOUBLE,
	pmi010703 DOUBLE,
	pmi010800 DOUBLE,
	pmi010801 DOUBLE,
	pmi010802 DOUBLE,
	pmi010803 DOUBLE,
	pmi010900 DOUBLE,
	pmi011000 DOUBLE,
	pmi011100 DOUBLE,
	pmi011200 DOUBLE,
	pmi011300 DOUBLE,
	pmi011400 DOUBLE,
	pmi011500 DOUBLE,
	pmi011600 DOUBLE,
	pmi011700 DOUBLE,
	pmi011800 DOUBLE,
	pmi011900 DOUBLE,
	pmi012000 DOUBLE,
	pmi020100 DOUBLE,
	pmi020101 DOUBLE,
	pmi020102 DOUBLE,
	pmi020200 DOUBLE,
	pmi020201 DOUBLE,
	pmi020202 DOUBLE,
	pmi020300 DOUBLE,
	pmi020301 DOUBLE,
	pmi020302 DOUBLE,
	pmi020400 DOUBLE,
	pmi020401 DOUBLE,
	pmi020402 DOUBLE,
	pmi020500 DOUBLE,
	pmi020501 DOUBLE,
	pmi020502 DOUBLE,
	pmi020600 DOUBLE,
	pmi020601 DOUBLE,
	pmi020602 DOUBLE,
	pmi020700 DOUBLE,
	pmi020800 DOUBLE,
	pmi020900 DOUBLE,
	pmi021000 DOUBLE,
	pmi030000 DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'cn_ppi' ( 
	month TIMESTAMP,
	ppi_yoy DOUBLE,
	ppi_mp_yoy DOUBLE,
	ppi_mp_qm_yoy DOUBLE,
	ppi_mp_rm_yoy DOUBLE,
	ppi_mp_p_yoy DOUBLE,
	ppi_cg_yoy DOUBLE,
	ppi_cg_f_yoy DOUBLE,
	ppi_cg_c_yoy DOUBLE,
	ppi_cg_adu_yoy DOUBLE,
	ppi_cg_dcg_yoy DOUBLE,
	ppi_mom DOUBLE,
	ppi_mp_mom DOUBLE,
	ppi_mp_qm_mom DOUBLE,
	ppi_mp_rm_mom DOUBLE,
	ppi_mp_p_mom DOUBLE,
	ppi_cg_mom DOUBLE,
	ppi_cg_f_mom DOUBLE,
	ppi_cg_c_mom DOUBLE,
	ppi_cg_adu_mom DOUBLE,
	ppi_cg_dcg_mom DOUBLE,
	ppi_accu DOUBLE,
	ppi_mp_accu DOUBLE,
	ppi_mp_qm_accu DOUBLE,
	ppi_mp_rm_accu DOUBLE,
	ppi_mp_p_accu DOUBLE,
	ppi_cg_accu DOUBLE,
	ppi_cg_f_accu DOUBLE,
	ppi_cg_c_accu DOUBLE,
	ppi_cg_adu_accu DOUBLE,
	ppi_cg_dcg_accu DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'cn_ppi_backup_20260928_172516_507534' ( 
	month TIMESTAMP,
	ppi_yoy DOUBLE,
	ppi_mp_yoy DOUBLE,
	ppi_mp_qm_yoy DOUBLE,
	ppi_mp_rm_yoy DOUBLE,
	ppi_mp_p_yoy DOUBLE,
	ppi_cg_yoy DOUBLE,
	ppi_cg_f_yoy DOUBLE,
	ppi_cg_c_yoy DOUBLE,
	ppi_cg_adu_yoy DOUBLE,
	ppi_cg_dcg_yoy DOUBLE,
	ppi_mom DOUBLE,
	ppi_mp_mom DOUBLE,
	ppi_mp_qm_mom DOUBLE,
	ppi_mp_rm_mom DOUBLE,
	ppi_mp_p_mom DOUBLE,
	ppi_cg_mom DOUBLE,
	ppi_cg_f_mom DOUBLE,
	ppi_cg_c_mom DOUBLE,
	ppi_cg_adu_mom DOUBLE,
	ppi_cg_dcg_mom DOUBLE,
	ppi_accu DOUBLE,
	ppi_mp_accu DOUBLE,
	ppi_mp_qm_accu DOUBLE,
	ppi_mp_rm_accu DOUBLE,
	ppi_mp_p_accu DOUBLE,
	ppi_cg_accu DOUBLE,
	ppi_cg_f_accu DOUBLE,
	ppi_cg_c_accu DOUBLE,
	ppi_cg_adu_accu DOUBLE,
	ppi_cg_dcg_accu DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'company_intrinsic_valuation_consensus' ( 
	valuation_date TIMESTAMP,
	ts_code SYMBOL,
	name STRING,
	industry_l2 SYMBOL,
	route_id SYMBOL,
	market_price DOUBLE,
	total_mv DOUBLE,
	pe_ttm DOUBLE,
	pb DOUBLE,
	fair_value_low DOUBLE,
	fair_value_base DOUBLE,
	fair_value_high DOUBLE,
	upside_low DOUBLE,
	upside_base DOUBLE,
	upside_high DOUBLE,
	configured_models INT,
	calculated_models INT,
	eligible_model_weight DOUBLE,
	consensus_confidence DOUBLE,
	model_dispersion DOUBLE,
	consensus_status SYMBOL,
	route_version SYMBOL,
	assumption_version SYMBOL,
	run_id SYMBOL
) timestamp(valuation_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(valuation_date,ts_code,route_version,assumption_version);

CREATE TABLE 'company_intrinsic_valuation_model' ( 
	valuation_date TIMESTAMP,
	ts_code SYMBOL,
	name STRING,
	industry_l2 SYMBOL,
	route_id SYMBOL,
	model_id SYMBOL,
	eligibility_status SYMBOL,
	eligibility_reason STRING,
	market_price DOUBLE,
	fair_value_low DOUBLE,
	fair_value_base DOUBLE,
	fair_value_high DOUBLE,
	upside_low DOUBLE,
	upside_base DOUBLE,
	upside_high DOUBLE,
	model_confidence DOUBLE,
	model_weight_config DOUBLE,
	explicit_growth_base DOUBLE,
	terminal_growth_base DOUBLE,
	cost_of_equity DOUBLE,
	wacc DOUBLE,
	risk_free_rate DOUBLE,
	beta DOUBLE,
	beta_observations DOUBLE,
	report_end_date TIMESTAMP,
	report_ann_date TIMESTAMP,
	route_version SYMBOL,
	assumption_version SYMBOL,
	run_id SYMBOL
) timestamp(valuation_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(valuation_date,ts_code,model_id,route_version,assumption_version);

CREATE TABLE 'cyq_chips' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	price DOUBLE[],
	percent DOUBLE[]
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'cyq_perf' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	winner_rate DOUBLE,
	weight_avg DOUBLE,
	cost_5pct DOUBLE,
	cost_15pct DOUBLE,
	cost_50pct DOUBLE,
	cost_85pct DOUBLE,
	cost_95pct DOUBLE,
	his_low DOUBLE,
	his_high DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'daily' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	pre_close DOUBLE,
	change DOUBLE,
	pct_chg DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	ah_vol DOUBLE,
	ah_amount DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'daily_basic' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	close DOUBLE,
	turnover_rate DOUBLE,
	turnover_rate_f DOUBLE,
	volume_ratio DOUBLE,
	pe DOUBLE,
	pe_ttm DOUBLE,
	pb DOUBLE,
	ps DOUBLE,
	ps_ttm DOUBLE,
	dv_ratio DOUBLE,
	dv_ttm DOUBLE,
	total_share DOUBLE,
	float_share DOUBLE,
	free_share DOUBLE,
	total_mv DOUBLE,
	circ_mv DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'daily_basic_backup_20260928_185816_917214' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	close DOUBLE,
	turnover_rate DOUBLE,
	turnover_rate_f DOUBLE,
	volume_ratio DOUBLE,
	pe DOUBLE,
	pe_ttm DOUBLE,
	pb DOUBLE,
	ps DOUBLE,
	ps_ttm DOUBLE,
	dv_ratio DOUBLE,
	dv_ttm DOUBLE,
	total_share DOUBLE,
	float_share DOUBLE,
	free_share DOUBLE,
	total_mv DOUBLE,
	circ_mv DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'daily_stock_analysis' ( 
	ts_code SYMBOL,
	analysis_date TIMESTAMP,
	content STRING,
	model_name SYMBOL,
	updated_at TIMESTAMP
) timestamp(updated_at) PARTITION BY MONTH;

CREATE TABLE 'dc_index' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	name STRING,
	leading STRING,
	leading_code STRING,
	pct_change DOUBLE,
	leading_pct DOUBLE,
	total_mv DOUBLE,
	turnover_rate DOUBLE,
	up_num INT,
	down_num INT
) timestamp(trade_date) PARTITION BY YEAR;

CREATE TABLE 'dc_index_backup_20260928_174222_987870' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	name STRING,
	leading STRING,
	leading_code STRING,
	pct_change DOUBLE,
	leading_pct DOUBLE,
	total_mv DOUBLE,
	turnover_rate DOUBLE,
	up_num INT,
	down_num INT
) timestamp(trade_date) PARTITION BY YEAR;

CREATE TABLE 'disclosure_date' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	end_date STRING,
	pre_date STRING,
	actual_date STRING,
	modify_date STRING,
	update_time TIMESTAMP
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date);

CREATE TABLE 'dividend' ( 
	ts_code SYMBOL,
	end_date STRING,
	ann_date TIMESTAMP,
	div_proc STRING,
	stk_div DOUBLE,
	stk_bo_rate DOUBLE,
	stk_co_rate DOUBLE,
	cash_div DOUBLE,
	cash_div_tax DOUBLE,
	record_date STRING,
	ex_date STRING,
	pay_date STRING,
	div_listdate STRING,
	imp_ann_date STRING,
	base_date STRING,
	base_share DOUBLE,
	update_time TIMESTAMP
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,end_date,ann_date);

CREATE TABLE 'eco_cal' ( 
	date TIMESTAMP,
	time STRING,
	currency SYMBOL,
	country SYMBOL,
	event STRING,
	value STRING,
	pre_value STRING,
	fore_value STRING
) timestamp(date) PARTITION BY YEAR;

CREATE TABLE 'eco_cal_backup_20260928_181221_079961' ( 
	date TIMESTAMP,
	time STRING,
	currency SYMBOL,
	country SYMBOL,
	event STRING,
	value STRING,
	pre_value STRING,
	fore_value STRING
) timestamp(date) PARTITION BY YEAR;

CREATE TABLE 'equity_style_monthly' ( 
	month TIMESTAMP,
	hs300_ret_1m DOUBLE,
	zz500_ret_1m DOUBLE,
	all_a_ret_1m DOUBLE,
	cs1000_ret_1m DOUBLE,
	small_large_ret_1m DOUBLE,
	mid_large_ret_1m DOUBLE,
	growth_ret_1m DOUBLE,
	value_ret_1m DOUBLE,
	growth_value_ret_1m DOUBLE,
	energy_ret_1m DOUBLE,
	materials_ret_1m DOUBLE,
	industrials_ret_1m DOUBLE,
	consumer_discretionary_ret_1m DOUBLE,
	consumer_staples_ret_1m DOUBLE,
	healthcare_ret_1m DOUBLE,
	financials_ret_1m DOUBLE,
	it_ret_1m DOUBLE,
	telecom_ret_1m DOUBLE,
	utilities_ret_1m DOUBLE,
	energy_vs_all_a_1m DOUBLE,
	materials_vs_all_a_1m DOUBLE,
	industrials_vs_all_a_1m DOUBLE,
	consumer_discretionary_vs_all_a_1m DOUBLE,
	consumer_staples_vs_all_a_1m DOUBLE,
	healthcare_vs_all_a_1m DOUBLE,
	financials_vs_all_a_1m DOUBLE,
	it_vs_all_a_1m DOUBLE,
	telecom_vs_all_a_1m DOUBLE,
	utilities_vs_all_a_1m DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'etf_adj' ( 
	ts_code SYMBOL,
	adj_factor DOUBLE,
	timestamp TIMESTAMP
) timestamp(timestamp) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,timestamp);

CREATE TABLE 'etf_basic' ( 
	ts_code SYMBOL,
	name STRING,
	management STRING,
	custodian STRING,
	fund_type STRING,
	found_date STRING,
	due_date STRING,
	list_date STRING,
	issue_date STRING,
	delist_date STRING,
	issue_amount DOUBLE,
	m_fee DOUBLE,
	c_fee DOUBLE,
	duration_year DOUBLE,
	p_value DOUBLE,
	min_amount DOUBLE,
	exp_return DOUBLE,
	benchmark STRING,
	status SYMBOL,
	invest_type STRING,
	type STRING,
	trustee STRING,
	purc_startdate STRING,
	redm_startdate STRING,
	market SYMBOL,
	timestamp TIMESTAMP,
	update_time TIMESTAMP
) timestamp(timestamp) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,timestamp);

CREATE TABLE 'etf_daily' ( 
	ts_code SYMBOL,
	timestamp TIMESTAMP_NS,
	pre_close DOUBLE,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	change DOUBLE,
	pct_chg DOUBLE,
	vol DOUBLE,
	amount DOUBLE
) timestamp(timestamp) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,timestamp);

CREATE TABLE 'etf_factor' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	pre_close DOUBLE,
	change DOUBLE,
	pct_change DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	asi_bfq DOUBLE,
	asit_bfq DOUBLE,
	bbi_bfq DOUBLE,
	bias1_bfq DOUBLE,
	bias2_bfq DOUBLE,
	bias3_bfq DOUBLE,
	brar_ar_bfq DOUBLE,
	brar_br_bfq DOUBLE,
	cr_bfq DOUBLE,
	dfma_dif_bfq DOUBLE,
	dfma_difma_bfq DOUBLE,
	dpo_bfq DOUBLE,
	madpo_bfq DOUBLE,
	ema_bfq_5 DOUBLE,
	ema_bfq_10 DOUBLE,
	ema_bfq_20 DOUBLE,
	ema_bfq_30 DOUBLE,
	ema_bfq_60 DOUBLE,
	ema_bfq_90 DOUBLE,
	ema_bfq_250 DOUBLE,
	emv_bfq DOUBLE,
	maemv_bfq DOUBLE,
	expma_12_bfq DOUBLE,
	expma_50_bfq DOUBLE,
	ktn_down_bfq DOUBLE,
	ktn_mid_bfq DOUBLE,
	ktn_upper_bfq DOUBLE,
	ma_bfq_5 DOUBLE,
	ma_bfq_10 DOUBLE,
	ma_bfq_20 DOUBLE,
	ma_bfq_30 DOUBLE,
	ma_bfq_60 DOUBLE,
	ma_bfq_90 DOUBLE,
	ma_bfq_250 DOUBLE,
	macd_bfq DOUBLE,
	macd_dif_bfq DOUBLE,
	macd_dea_bfq DOUBLE,
	kdj_bfq DOUBLE,
	kdj_k_bfq DOUBLE,
	kdj_d_bfq DOUBLE,
	rsi_bfq_6 DOUBLE,
	rsi_bfq_12 DOUBLE,
	rsi_bfq_24 DOUBLE,
	boll_upper_bfq DOUBLE,
	boll_mid_bfq DOUBLE,
	boll_lower_bfq DOUBLE,
	atr_bfq DOUBLE,
	cci_bfq DOUBLE,
	dmi_pdi_bfq DOUBLE,
	dmi_mdi_bfq DOUBLE,
	dmi_adx_bfq DOUBLE,
	dmi_adxr_bfq DOUBLE,
	mass_bfq DOUBLE,
	ma_mass_bfq DOUBLE,
	mfi_bfq DOUBLE,
	mtm_bfq DOUBLE,
	mtmma_bfq DOUBLE,
	obv_bfq DOUBLE,
	psy_bfq DOUBLE,
	psyma_bfq DOUBLE,
	roc_bfq DOUBLE,
	maroc_bfq DOUBLE,
	taq_down_bfq DOUBLE,
	taq_mid_bfq DOUBLE,
	taq_up_bfq DOUBLE,
	trix_bfq DOUBLE,
	trma_bfq DOUBLE,
	vr_bfq DOUBLE,
	wr_bfq DOUBLE,
	wr1_bfq DOUBLE,
	xsii_td1_bfq DOUBLE,
	xsii_td2_bfq DOUBLE,
	xsii_td3_bfq DOUBLE,
	xsii_td4_bfq DOUBLE,
	updays DOUBLE,
	downdays DOUBLE,
	lowdays DOUBLE,
	topdays DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'etf_market_overview_daily_cache' ( 
	trade_date TIMESTAMP,
	etf_count LONG,
	total_share DOUBLE,
	total_size_yi DOUBLE,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'etf_portfolio' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	end_date TIMESTAMP,
	symbol SYMBOL,
	mkv DOUBLE,
	amount DOUBLE,
	stk_mkv_ratio DOUBLE,
	stk_float_ratio DOUBLE,
	update_time TIMESTAMP
) timestamp(end_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date,symbol);

CREATE TABLE 'etf_share' ( 
	ts_code SYMBOL,
	timestamp TIMESTAMP,
	fd_share DOUBLE,
	fund_type STRING,
	market SYMBOL,
	update_time TIMESTAMP
) timestamp(timestamp) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,timestamp);

CREATE TABLE 'etf_share_backup_20260928_174145_144587' ( 
	ts_code SYMBOL,
	timestamp TIMESTAMP,
	fd_share DOUBLE,
	fund_type STRING,
	market SYMBOL,
	update_time TIMESTAMP
) timestamp(timestamp) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,timestamp);

CREATE TABLE 'exchange_calendar' ( 
	exchange SYMBOL,
	cal_date TIMESTAMP,
	is_open INT,
	pretrade_date STRING
) timestamp(cal_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(exchange,cal_date);

CREATE TABLE 'express' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	end_date STRING,
	revenue DOUBLE,
	operate_profit DOUBLE,
	total_profit DOUBLE,
	n_income DOUBLE,
	total_assets DOUBLE,
	total_hldr_eqy_exc_min_int DOUBLE,
	diluted_eps DOUBLE,
	diluted_roe DOUBLE,
	yoy_net_profit DOUBLE,
	bps DOUBLE,
	yoy_sales DOUBLE,
	yoy_op DOUBLE,
	yoy_tp DOUBLE,
	yoy_dedu_np DOUBLE,
	yoy_eps DOUBLE,
	yoy_roe DOUBLE,
	growth_assets DOUBLE,
	yoy_equity DOUBLE,
	growth_bps DOUBLE,
	open_net_assets DOUBLE,
	open_bps DOUBLE,
	or_last_year DOUBLE,
	op_last_year DOUBLE,
	tp_last_year DOUBLE,
	np_last_year DOUBLE,
	eps_last_year DOUBLE,
	perf_summary STRING,
	is_audit INT,
	remark STRING
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date);

CREATE TABLE 'express_period_meta' ( 
	period SYMBOL,
	last_fetched_at TIMESTAMP,
	row_count INT
);

CREATE TABLE 'factor_catalog' ( 
	updated_at TIMESTAMP,
	factor_id SYMBOL,
	definition_version SYMBOL,
	name_zh STRING,
	name_en STRING,
	factor_family SYMBOL,
	role SYMBOL,
	lifecycle_status SYMBOL,
	direction SYMBOL,
	frequency SYMBOL,
	formula STRING,
	source_ref STRING,
	definition_digest SYMBOL,
	enabled BOOLEAN
) timestamp(updated_at) PARTITION BY NONE BYPASS WAL;

CREATE TABLE 'factor_corr_snapshot' ( 
	snapshot_date TIMESTAMP,
	factor_id_1 SYMBOL,
	factor_id_2 SYMBOL,
	definition_version SYMBOL,
	universe_id SYMBOL,
	rank_corr DOUBLE,
	corr_cluster SYMBOL,
	run_id SYMBOL
) timestamp(snapshot_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(snapshot_date,factor_id_1,factor_id_2,definition_version,universe_id,run_id);

CREATE TABLE 'factor_daily_metric' ( 
	trade_date TIMESTAMP,
	universe_id SYMBOL,
	universe_version SYMBOL,
	factor_id SYMBOL,
	definition_version SYMBOL,
	label_definition SYMBOL,
	horizon INT,
	evaluation_profile SYMBOL,
	label_price_basis SYMBOL,
	maturity_at TIMESTAMP,
	rank_ic DOUBLE,
	top_minus_bottom DOUBLE,
	win BOOLEAN,
	decile_monotonicity DOUBLE,
	factor_autocorrelation DOUBLE,
	decile_1_mean_return DOUBLE,
	decile_2_mean_return DOUBLE,
	decile_3_mean_return DOUBLE,
	decile_4_mean_return DOUBLE,
	decile_5_mean_return DOUBLE,
	decile_6_mean_return DOUBLE,
	decile_7_mean_return DOUBLE,
	decile_8_mean_return DOUBLE,
	decile_9_mean_return DOUBLE,
	decile_10_mean_return DOUBLE,
	pure_rank_ic DOUBLE,
	pure_top_minus_bottom DOUBLE,
	pure_win BOOLEAN,
	pure_decile_monotonicity DOUBLE,
	pure_model_r_squared DOUBLE,
	control_coverage DOUBLE,
	control_definition_version SYMBOL,
	coverage DOUBLE,
	sample_count INT,
	evaluation_sample_hash SYMBOL,
	membership_as_of TIMESTAMP,
	available_at TIMESTAMP,
	data_quality_status SYMBOL,
	l2_source_lineage_digest SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,universe_id,universe_version,factor_id,definition_version,label_definition,horizon,evaluation_profile);

CREATE TABLE 'factor_definition' ( 
	registered_at TIMESTAMP,
	factor_id SYMBOL,
	definition_version SYMBOL,
	name_zh STRING,
	name_en STRING,
	description_zh STRING,
	description_en STRING,
	family_id SYMBOL,
	family_name_zh STRING,
	family_name_en STRING,
	entity_type SYMBOL,
	frequency SYMBOL,
	role SYMBOL,
	variant_id SYMBOL,
	is_canonical BOOLEAN,
	validation_profile SYMBOL,
	lifecycle_status SYMBOL,
	transform SYMBOL,
	pit_policy SYMBOL,
	lineage_parent_id SYMBOL,
	lineage_parent_version SYMBOL,
	change_reason STRING,
	maturity_status SYMBOL,
	direction SYMBOL,
	lookback_periods INT,
	formula STRING,
	economic_logic_zh STRING,
	economic_logic_en STRING,
	aggregation STRING,
	limitation STRING,
	origin SYMBOL,
	methodology_version STRING,
	benchmark_role SYMBOL,
	source_assets STRING,
	category SYMBOL,
	horizon STRING,
	parameters STRING,
	neutralize_policy SYMBOL,
	normalize_policy SYMBOL,
	decay_policy SYMBOL,
	owner SYMBOL,
	aliases STRING,
	enabled BOOLEAN
) timestamp(registered_at) PARTITION BY NONE BYPASS WAL;

CREATE TABLE 'factor_dependency' ( 
	registered_at TIMESTAMP,
	factor_id SYMBOL,
	definition_version SYMBOL,
	dependency_id SYMBOL,
	dependency_kind SYMBOL,
	relation SYMBOL,
	required BOOLEAN,
	run_id SYMBOL
) timestamp(registered_at) PARTITION BY YEAR
DEDUP UPSERT KEYS(registered_at,factor_id,definition_version,dependency_id,relation);

CREATE TABLE 'factor_ic_daily' ( 
	trade_date TIMESTAMP,
	factor_id SYMBOL,
	universe_id SYMBOL,
	signal_date TIMESTAMP,
	maturity_date TIMESTAMP,
	available_at TIMESTAMP,
	definition_version SYMBOL,
	factor_family SYMBOL,
	source_group SYMBOL,
	direction_prior SYMBOL,
	horizon SYMBOL,
	raw_rank_ic DOUBLE,
	rank_ic DOUBLE,
	direction_adjusted_rank_ic DOUBLE,
	top_minus_bottom DOUBLE,
	top_bucket_mean DOUBLE,
	top_bucket_worst DOUBLE,
	positive_top_rate DOUBLE,
	decile_long_short DOUBLE,
	decile_monotonicity DOUBLE,
	factor_autocorrelation DOUBLE,
	decile_1_mean_return DOUBLE,
	decile_2_mean_return DOUBLE,
	decile_3_mean_return DOUBLE,
	decile_4_mean_return DOUBLE,
	decile_5_mean_return DOUBLE,
	decile_6_mean_return DOUBLE,
	decile_7_mean_return DOUBLE,
	decile_8_mean_return DOUBLE,
	decile_9_mean_return DOUBLE,
	decile_10_mean_return DOUBLE,
	coverage DOUBLE,
	tradable_rate DOUBLE,
	sample_count INT,
	run_id SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,factor_id,universe_id,definition_version,horizon,run_id);

CREATE TABLE 'factor_materialization_run' ( 
	period_end TIMESTAMP,
	period_start TIMESTAMP,
	factor_id SYMBOL,
	definition_version SYMBOL,
	frequency SYMBOL,
	entity_scope SYMBOL,
	universe_id SYMBOL,
	input_fingerprint SYMBOL,
	action SYMBOL,
	status SYMBOL,
	expected_entity_count INT,
	actual_entity_count INT,
	coverage_ratio DOUBLE,
	reason STRING,
	run_id SYMBOL,
	started_at TIMESTAMP,
	completed_at TIMESTAMP
) timestamp(period_end) PARTITION BY MONTH
DEDUP UPSERT KEYS(period_end,factor_id,definition_version,frequency,entity_scope,universe_id,input_fingerprint);

CREATE TABLE 'factor_monitor_daily' ( 
	signal_date TIMESTAMP,
	maturity_date TIMESTAMP,
	factor_id SYMBOL,
	definition_version SYMBOL,
	universe_id SYMBOL,
	horizon SYMBOL,
	direction_prior SYMBOL,
	effective_direction SYMBOL,
	rank_ic DOUBLE,
	rank_ic_p_value DOUBLE,
	sample_count INT,
	universe_count INT,
	coverage DOUBLE,
	group_1_return DOUBLE,
	group_2_return DOUBLE,
	group_3_return DOUBLE,
	group_4_return DOUBLE,
	group_5_return DOUBLE,
	top_minus_bottom_return DOUBLE,
	oriented_top_minus_bottom_return DOUBLE,
	monotonicity_score DOUBLE,
	factor_autocorr DOUBLE,
	data_quality_status SYMBOL,
	run_id SYMBOL,
	computed_at TIMESTAMP
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(signal_date,factor_id,definition_version,universe_id,horizon);

CREATE TABLE 'factor_monitor_summary' ( 
	as_of_date TIMESTAMP,
	factor_id SYMBOL,
	definition_version SYMBOL,
	universe_id SYMBOL,
	horizon SYMBOL,
	window SYMBOL,
	direction_prior SYMBOL,
	effective_direction SYMBOL,
	rank_ic_mean DOUBLE,
	rank_ic_std DOUBLE,
	icir DOUBLE,
	rank_ic_win_rate DOUBLE,
	oriented_rank_ic_mean DOUBLE,
	oriented_icir DOUBLE,
	top_minus_bottom_mean DOUBLE,
	top_minus_bottom_win_rate DOUBLE,
	monotonicity_score DOUBLE,
	cumulative_ic DOUBLE,
	cumulative_long_short_return DOUBLE,
	factor_autocorr DOUBLE,
	negative_ic_ratio DOUBLE,
	significant_negative_ic_ratio DOUBLE,
	loss_period_ratio_when_negative_ic DOUBLE,
	loss_magnitude_share_when_negative_ic DOUBLE,
	sample_days INT,
	mean_universe_coverage DOUBLE,
	status SYMBOL,
	data_quality_status SYMBOL,
	run_id SYMBOL,
	computed_at TIMESTAMP,
	ic_win_rate DOUBLE,
	top_bottom_return DOUBLE,
	top_bottom_win_rate DOUBLE,
	group_1_return DOUBLE,
	group_2_return DOUBLE,
	group_3_return DOUBLE,
	group_4_return DOUBLE,
	group_5_return DOUBLE,
	sample_count INT
) timestamp(as_of_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(as_of_date,factor_id,definition_version,universe_id,horizon,window);

CREATE TABLE 'factor_observation' ( 
	signal_date TIMESTAMP,
	entity_type SYMBOL,
	entity_id SYMBOL,
	factor_id SYMBOL,
	definition_version SYMBOL,
	factor_value DOUBLE,
	available_at TIMESTAMP,
	quality_status SYMBOL,
	scope SYMBOL,
	l2_source_lineage_digest SYMBOL
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(signal_date,entity_type,entity_id,factor_id,definition_version);

CREATE TABLE 'factor_platform_run' ( 
	run_at TIMESTAMP,
	run_id SYMBOL,
	consumer SYMBOL,
	start_date TIMESTAMP,
	end_date TIMESTAMP,
	definition_version SYMBOL,
	factor_count INT,
	promoted_count INT,
	notes STRING,
	input_fingerprint SYMBOL
) timestamp(run_at) PARTITION BY YEAR
DEDUP UPSERT KEYS(run_at,run_id);

CREATE TABLE 'factor_regime_daily' ( 
	trade_date TIMESTAMP,
	factor_id SYMBOL,
	universe_id SYMBOL,
	definition_version SYMBOL,
	horizon SYMBOL,
	regime_state SYMBOL,
	effective_weight DOUBLE,
	mean_ic_20 DOUBLE,
	mean_ic_40 DOUBLE,
	mean_ic_60 DOUBLE,
	positive_ratio_20 DOUBLE,
	top_minus_bottom_20 DOUBLE,
	run_id SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,factor_id,universe_id,definition_version,horizon,run_id);

CREATE TABLE 'factor_registry' ( 
	updated_at TIMESTAMP,
	run_id SYMBOL,
	factor_id SYMBOL,
	definition_version SYMBOL,
	asset_type SYMBOL,
	source_group SYMBOL,
	source_table SYMBOL,
	source_column SYMBOL,
	formula STRING,
	factor_family SYMBOL,
	frequency SYMBOL,
	role SYMBOL,
	variant_id SYMBOL,
	is_canonical BOOLEAN,
	validation_profile SYMBOL,
	lifecycle_status SYMBOL,
	lineage_parent_id SYMBOL,
	lineage_parent_version SYMBOL,
	change_reason STRING,
	direction_prior SYMBOL,
	transform SYMBOL,
	pit_policy SYMBOL,
	enabled BOOLEAN,
	promote_to_db BOOLEAN,
	promoted BOOLEAN,
	evidence_score DOUBLE,
	category SYMBOL,
	horizon STRING,
	lookback_periods INT,
	parameters STRING,
	neutralize_policy SYMBOL,
	normalize_policy SYMBOL,
	decay_policy SYMBOL,
	owner SYMBOL,
	aliases STRING
) timestamp(updated_at) PARTITION BY NONE BYPASS WAL;

CREATE TABLE 'factor_usage' ( 
	observed_at TIMESTAMP,
	factor_id SYMBOL,
	definition_version SYMBOL,
	consumer_type SYMBOL,
	consumer_id SYMBOL,
	usage_role SYMBOL,
	lifecycle_status SYMBOL,
	run_id SYMBOL
) timestamp(observed_at) PARTITION BY YEAR
DEDUP UPSERT KEYS(observed_at,factor_id,definition_version,consumer_type,consumer_id,usage_role);

CREATE TABLE 'factor_validation_daily' ( 
	trade_date TIMESTAMP,
	factor_id SYMBOL,
	universe_id SYMBOL,
	definition_version SYMBOL,
	validation_profile SYMBOL,
	metric_name SYMBOL,
	metric_value DOUBLE,
	threshold DOUBLE,
	status SYMBOL,
	sample_count INT,
	run_id SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,factor_id,universe_id,definition_version,validation_profile,metric_name,run_id);

CREATE TABLE 'fina_audit' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	end_date STRING,
	audit_result STRING,
	audit_fees DOUBLE,
	audit_agency STRING,
	audit_sign STRING
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date);

CREATE TABLE 'fina_indicator' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	end_date STRING,
	eps DOUBLE,
	dt_eps DOUBLE,
	total_revenue_ps DOUBLE,
	revenue_ps DOUBLE,
	capital_rese_ps DOUBLE,
	surplus_rese_ps DOUBLE,
	undist_profit_ps DOUBLE,
	extra_item DOUBLE,
	profit_dedt DOUBLE,
	gross_margin DOUBLE,
	current_ratio DOUBLE,
	quick_ratio DOUBLE,
	cash_ratio DOUBLE,
	invturn_days DOUBLE,
	arturn_days DOUBLE,
	inv_turn DOUBLE,
	ar_turn DOUBLE,
	ca_turn DOUBLE,
	fa_turn DOUBLE,
	assets_turn DOUBLE,
	op_income DOUBLE,
	valuechange_income DOUBLE,
	interst_income DOUBLE,
	daa DOUBLE,
	ebit DOUBLE,
	ebitda DOUBLE,
	fcff DOUBLE,
	fcfe DOUBLE,
	current_exint DOUBLE,
	noncurrent_exint DOUBLE,
	interestdebt DOUBLE,
	netdebt DOUBLE,
	tangible_asset DOUBLE,
	working_capital DOUBLE,
	networking_capital DOUBLE,
	invest_capital DOUBLE,
	retained_earnings DOUBLE,
	diluted2_eps DOUBLE,
	bps DOUBLE,
	ocfps DOUBLE,
	retainedps DOUBLE,
	cfps DOUBLE,
	ebit_ps DOUBLE,
	fcff_ps DOUBLE,
	fcfe_ps DOUBLE,
	netprofit_margin DOUBLE,
	grossprofit_margin DOUBLE,
	cogs_of_sales DOUBLE,
	expense_of_sales DOUBLE,
	profit_to_gr DOUBLE,
	saleexp_to_gr DOUBLE,
	adminexp_of_gr DOUBLE,
	finaexp_of_gr DOUBLE,
	impai_ttm DOUBLE,
	gc_of_gr DOUBLE,
	op_of_gr DOUBLE,
	ebit_of_gr DOUBLE,
	roe DOUBLE,
	roe_waa DOUBLE,
	roe_dt DOUBLE,
	roa DOUBLE,
	npta DOUBLE,
	roic DOUBLE,
	roe_yearly DOUBLE,
	roa2_yearly DOUBLE,
	roe_avg DOUBLE,
	opincome_of_ebt DOUBLE,
	investincome_of_ebt DOUBLE,
	n_op_profit_of_ebt DOUBLE,
	tax_to_ebt DOUBLE,
	dtprofit_to_profit DOUBLE,
	salescash_to_or DOUBLE,
	ocf_to_or DOUBLE,
	ocf_to_opincome DOUBLE,
	capitalized_to_da DOUBLE,
	debt_to_assets DOUBLE,
	assets_to_eqt DOUBLE,
	dp_assets_to_eqt DOUBLE,
	ca_to_assets DOUBLE,
	nca_to_assets DOUBLE,
	tbassets_to_totalassets DOUBLE,
	int_to_talcap DOUBLE,
	eqt_to_talcapital DOUBLE,
	currentdebt_to_debt DOUBLE,
	longdeb_to_debt DOUBLE,
	ocf_to_shortdebt DOUBLE,
	debt_to_eqt DOUBLE,
	eqt_to_debt DOUBLE,
	eqt_to_interestdebt DOUBLE,
	tangibleasset_to_debt DOUBLE,
	tangasset_to_intdebt DOUBLE,
	tangibleasset_to_netdebt DOUBLE,
	ocf_to_debt DOUBLE,
	ocf_to_interestdebt DOUBLE,
	ocf_to_netdebt DOUBLE,
	ebit_to_interest DOUBLE,
	longdebt_to_workingcapital DOUBLE,
	ebitda_to_debt DOUBLE,
	turn_days DOUBLE,
	roa_yearly DOUBLE,
	roa_dp DOUBLE,
	fixed_assets DOUBLE,
	profit_prefin_exp DOUBLE,
	non_op_profit DOUBLE,
	op_to_ebt DOUBLE,
	nop_to_ebt DOUBLE,
	ocf_to_profit DOUBLE,
	cash_to_liqdebt DOUBLE,
	cash_to_liqdebt_withinterest DOUBLE,
	op_to_liqdebt DOUBLE,
	op_to_debt DOUBLE,
	roic_yearly DOUBLE,
	total_fa_trun DOUBLE,
	profit_to_op DOUBLE,
	q_opincome DOUBLE,
	q_investincome DOUBLE,
	q_dtprofit DOUBLE,
	q_eps DOUBLE,
	q_netprofit_margin DOUBLE,
	q_gsprofit_margin DOUBLE,
	q_exp_to_sales DOUBLE,
	q_profit_to_gr DOUBLE,
	q_saleexp_to_gr DOUBLE,
	q_adminexp_to_gr DOUBLE,
	q_finaexp_to_gr DOUBLE,
	q_impair_to_gr_ttm DOUBLE,
	q_gc_to_gr DOUBLE,
	q_op_to_gr DOUBLE,
	q_roe DOUBLE,
	q_dt_roe DOUBLE,
	q_npta DOUBLE,
	q_opincome_to_ebt DOUBLE,
	q_investincome_to_ebt DOUBLE,
	q_dtprofit_to_profit DOUBLE,
	q_salescash_to_or DOUBLE,
	q_ocf_to_sales DOUBLE,
	q_ocf_to_or DOUBLE,
	basic_eps_yoy DOUBLE,
	dt_eps_yoy DOUBLE,
	cfps_yoy DOUBLE,
	op_yoy DOUBLE,
	ebt_yoy DOUBLE,
	netprofit_yoy DOUBLE,
	dt_netprofit_yoy DOUBLE,
	ocf_yoy DOUBLE,
	roe_yoy DOUBLE,
	bps_yoy DOUBLE,
	assets_yoy DOUBLE,
	eqt_yoy DOUBLE,
	tr_yoy DOUBLE,
	or_yoy DOUBLE,
	q_gr_yoy DOUBLE,
	q_gr_qoq DOUBLE,
	q_sales_yoy DOUBLE,
	q_sales_qoq DOUBLE,
	q_op_yoy DOUBLE,
	q_op_qoq DOUBLE,
	q_profit_yoy DOUBLE,
	q_profit_qoq DOUBLE,
	q_netprofit_yoy DOUBLE,
	q_netprofit_qoq DOUBLE,
	equity_yoy DOUBLE,
	rd_exp DOUBLE,
	update_flag STRING,
	yoy_equity DOUBLE,
	growth_assets DOUBLE,
	yoy_bps DOUBLE,
	growth_profit DOUBLE,
	yoy_eps DOUBLE,
	yoy_roe DOUBLE,
	yoy_netprofit_margin DOUBLE,
	yoy_netprofit DOUBLE,
	yoy_assets DOUBLE,
	yoy_tr DOUBLE,
	yoy_or DOUBLE
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date);

CREATE TABLE 'fina_indicator_period_meta' ( 
	period SYMBOL,
	last_fetched_at TIMESTAMP,
	row_count INT
);

CREATE TABLE 'fina_mainbz' ( 
	ts_code SYMBOL,
	end_date TIMESTAMP,
	bz_item STRING,
	bz_sales DOUBLE,
	bz_profit DOUBLE,
	bz_cost DOUBLE,
	curr_type STRING,
	update_flag STRING,
	bz_code VARCHAR
) timestamp(end_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,end_date,bz_item);

CREATE TABLE 'fina_mainbz_backup_20260928_181058_858233' ( 
	ts_code SYMBOL,
	end_date TIMESTAMP,
	bz_item STRING,
	bz_sales DOUBLE,
	bz_profit DOUBLE,
	bz_cost DOUBLE,
	curr_type STRING,
	update_flag STRING,
	bz_code VARCHAR
) timestamp(end_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,end_date,bz_item);

CREATE TABLE 'forecast' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	end_date STRING,
	type STRING,
	p_change_min DOUBLE,
	p_change_max DOUBLE,
	net_profit_min DOUBLE,
	net_profit_max DOUBLE,
	last_parent_net DOUBLE,
	first_ann_date STRING,
	summary STRING,
	change_reason STRING
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date);

CREATE TABLE 'forecast_backup_20260928_181158_217386' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	end_date STRING,
	type STRING,
	p_change_min DOUBLE,
	p_change_max DOUBLE,
	net_profit_min DOUBLE,
	net_profit_max DOUBLE,
	last_parent_net DOUBLE,
	first_ann_date STRING,
	summary STRING,
	change_reason STRING
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date);

CREATE TABLE 'forecast_period_meta' ( 
	period SYMBOL,
	last_fetched_at TIMESTAMP,
	row_count INT
);

CREATE TABLE 'ft_limit' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	name STRING,
	up_limit DOUBLE,
	down_limit DOUBLE,
	m_ratio DOUBLE,
	cont SYMBOL,
	exchange SYMBOL,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(trade_date,ts_code);

CREATE TABLE 'ft_limit_backup_20260928_174115_825717' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	name STRING,
	up_limit DOUBLE,
	down_limit DOUBLE,
	m_ratio DOUBLE,
	cont SYMBOL,
	exchange SYMBOL,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(trade_date,ts_code);

CREATE TABLE 'fut_basic' ( 
	ts_code SYMBOL,
	symbol SYMBOL,
	exchange SYMBOL,
	name STRING,
	fut_code SYMBOL,
	multiplier DOUBLE,
	trade_unit STRING,
	per_unit DOUBLE,
	quote_unit STRING,
	list_date STRING,
	delist_date STRING,
	d_month STRING,
	last_ddate STRING,
	trade_time_desc STRING,
	timestamp TIMESTAMP,
	update_time TIMESTAMP
) timestamp(timestamp) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,timestamp);

CREATE TABLE 'fut_daily' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	pre_close DOUBLE,
	pre_settle DOUBLE,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	settle DOUBLE,
	change1 DOUBLE,
	change2 DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	oi DOUBLE,
	oi_chg DOUBLE,
	delv_settle DOUBLE,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'fut_daily_backup_20260928_174113_511027' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	pre_close DOUBLE,
	pre_settle DOUBLE,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	settle DOUBLE,
	change1 DOUBLE,
	change2 DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	oi DOUBLE,
	oi_chg DOUBLE,
	delv_settle DOUBLE,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'fut_holding' ( 
	trade_date TIMESTAMP,
	symbol SYMBOL,
	broker SYMBOL,
	vol LONG,
	vol_chg LONG,
	long_hld LONG,
	long_chg LONG,
	short_hld LONG,
	short_chg LONG,
	exchange SYMBOL,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,symbol,broker);

CREATE TABLE 'fut_holding_backup_20260928_182407_398210' ( 
	trade_date TIMESTAMP,
	symbol SYMBOL,
	broker SYMBOL,
	vol LONG,
	vol_chg LONG,
	long_hld LONG,
	long_chg LONG,
	short_hld LONG,
	short_chg LONG,
	exchange SYMBOL,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,symbol,broker);

CREATE TABLE 'fut_index_conclusion_daily' ( 
	trade_date TIMESTAMP,
	fut_code SYMBOL,
	main_ts_code SYMBOL,
	mapping_ts_code SYMBOL,
	spot_index_code SYMBOL,
	direction SYMBOL,
	strength SYMBOL,
	style_tag SYMBOL,
	confidence DOUBLE,
	raw_score DOUBLE,
	summary STRING,
	risk_note STRING,
	drivers STRING,
	data_quality_flag SYMBOL,
	updated_at TIMESTAMP
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,fut_code);

CREATE TABLE 'fut_index_daily_snapshot' ( 
	trade_date TIMESTAMP,
	fut_code SYMBOL,
	main_ts_code SYMBOL,
	mapping_ts_code SYMBOL,
	spot_index_code SYMBOL,
	fut_close DOUBLE,
	fut_settle DOUBLE,
	spot_close DOUBLE,
	fut_ret_1d DOUBLE,
	spot_ret_1d DOUBLE,
	basis_points DOUBLE,
	basis_pct DOUBLE,
	annualized_basis_pct DOUBLE,
	days_to_expiry INT,
	vol DOUBLE,
	oi DOUBLE,
	oi_chg DOUBLE,
	top20_long DOUBLE,
	top20_short DOUBLE,
	top20_net DOUBLE,
	top20_net_chg DOUBLE,
	long_margin_rate DOUBLE,
	short_margin_rate DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	data_quality_flag SYMBOL,
	updated_at TIMESTAMP
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,fut_code);

CREATE TABLE 'fut_index_signal_daily' ( 
	trade_date TIMESTAMP,
	fut_code SYMBOL,
	main_ts_code SYMBOL,
	mapping_ts_code SYMBOL,
	basis_points DOUBLE,
	basis_pct DOUBLE,
	annualized_basis_pct DOUBLE,
	fut_ret_1d DOUBLE,
	spot_ret_1d DOUBLE,
	lead_spread_ret DOUBLE,
	oi_state SYMBOL,
	volume_oi_state SYMBOL,
	top20_long DOUBLE,
	top20_short DOUBLE,
	top20_net DOUBLE,
	top20_net_chg DOUBLE,
	signal_basis DOUBLE,
	signal_position DOUBLE,
	signal_structure DOUBLE,
	signal_strength DOUBLE,
	data_quality_flag SYMBOL,
	updated_at TIMESTAMP
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,fut_code);

CREATE TABLE 'fut_mapping' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	mapping_ts_code SYMBOL,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'fut_mapping_backup_20260928_174103_231873' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	mapping_ts_code SYMBOL,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'fut_settle' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	settle DOUBLE,
	trading_fee_rate DOUBLE,
	trading_fee DOUBLE,
	delivery_fee DOUBLE,
	b_hedging_margin_rate DOUBLE,
	s_hedging_margin_rate DOUBLE,
	long_margin_rate DOUBLE,
	short_margin_rate DOUBLE,
	offset_today_fee DOUBLE,
	exchange SYMBOL,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'fut_settle_backup_20260928_174110_795801' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	settle DOUBLE,
	trading_fee_rate DOUBLE,
	trading_fee DOUBLE,
	delivery_fee DOUBLE,
	b_hedging_margin_rate DOUBLE,
	s_hedging_margin_rate DOUBLE,
	long_margin_rate DOUBLE,
	short_margin_rate DOUBLE,
	offset_today_fee DOUBLE,
	exchange SYMBOL,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'hibor' ( 
	timestamp TIMESTAMP,
	on DOUBLE,
	1w DOUBLE,
	2w DOUBLE,
	1m DOUBLE,
	2m DOUBLE,
	3m DOUBLE,
	6m DOUBLE,
	12m DOUBLE
) timestamp(timestamp) PARTITION BY YEAR
DEDUP UPSERT KEYS(timestamp);

CREATE TABLE 'hibor_backup_20260928_174101_121465' ( 
	timestamp TIMESTAMP,
	on DOUBLE,
	1w DOUBLE,
	2w DOUBLE,
	1m DOUBLE,
	2m DOUBLE,
	3m DOUBLE,
	6m DOUBLE,
	12m DOUBLE
) timestamp(timestamp) PARTITION BY YEAR
DEDUP UPSERT KEYS(timestamp);

CREATE TABLE 'income' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	f_ann_date STRING,
	end_date STRING,
	report_type STRING,
	comp_type STRING,
	end_type STRING,
	basic_eps DOUBLE,
	diluted_eps DOUBLE,
	total_revenue DOUBLE,
	revenue DOUBLE,
	int_income DOUBLE,
	prem_earned DOUBLE,
	comm_income DOUBLE,
	n_commis_income DOUBLE,
	n_oth_income DOUBLE,
	n_oth_b_income DOUBLE,
	prem_income DOUBLE,
	out_prem DOUBLE,
	une_prem_reser DOUBLE,
	reins_income DOUBLE,
	n_sec_tb_income DOUBLE,
	n_sec_uw_income DOUBLE,
	n_asset_mg_income DOUBLE,
	oth_b_income DOUBLE,
	fv_value_chg_gain DOUBLE,
	invest_income DOUBLE,
	ass_invest_income DOUBLE,
	forex_gain DOUBLE,
	total_cogs DOUBLE,
	oper_cost DOUBLE,
	int_exp DOUBLE,
	comm_exp DOUBLE,
	biz_tax_surchg DOUBLE,
	sell_exp DOUBLE,
	admin_exp DOUBLE,
	fin_exp DOUBLE,
	assets_impair_loss DOUBLE,
	prem_refund DOUBLE,
	compens_payout DOUBLE,
	reser_insur_liab DOUBLE,
	div_payt DOUBLE,
	reins_exp DOUBLE,
	oper_exp DOUBLE,
	compens_payout_refu DOUBLE,
	insur_reser_refu DOUBLE,
	reins_cost_refund DOUBLE,
	other_bus_cost DOUBLE,
	operate_profit DOUBLE,
	non_oper_income DOUBLE,
	non_oper_exp DOUBLE,
	nca_disploss DOUBLE,
	total_profit DOUBLE,
	income_tax DOUBLE,
	n_income DOUBLE,
	n_income_attr_p DOUBLE,
	minority_gain DOUBLE,
	oth_compr_income DOUBLE,
	t_compr_income DOUBLE,
	compr_inc_attr_p DOUBLE,
	compr_inc_attr_m_s DOUBLE,
	ebit DOUBLE,
	ebitda DOUBLE,
	insurance_exp DOUBLE,
	undist_profit DOUBLE,
	distable_profit DOUBLE,
	rd_exp DOUBLE,
	fin_exp_int_exp DOUBLE,
	fin_exp_int_inc DOUBLE,
	transfer_surplus_rese DOUBLE,
	transfer_housing_imprest DOUBLE,
	transfer_oth DOUBLE,
	adj_lossgain DOUBLE,
	withdra_legal_surplus DOUBLE,
	withdra_legal_pubfund DOUBLE,
	withdra_biz_devfund DOUBLE,
	withdra_rese_fund DOUBLE,
	withdra_oth_ersu DOUBLE,
	workers_welfare DOUBLE,
	distr_profit_shrhder DOUBLE,
	prfshare_payable_dvd DOUBLE,
	comshare_payable_dvd DOUBLE,
	capit_comstock_div DOUBLE,
	net_after_nr_lp_correct DOUBLE,
	credit_impa_loss DOUBLE,
	net_expo_hedging_benefits DOUBLE,
	oth_impair_loss_assets DOUBLE,
	total_opcost DOUBLE,
	amodcost_fin_assets DOUBLE,
	oth_income DOUBLE,
	asset_disp_income DOUBLE,
	continued_net_profit DOUBLE,
	end_net_profit DOUBLE,
	update_flag STRING
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date);

CREATE TABLE 'income_period_meta' ( 
	period SYMBOL,
	last_fetched_at TIMESTAMP,
	row_count INT
);

CREATE TABLE 'index' ( 
	index_code SYMBOL,
	index_short_name STRING,
	index_full_name STRING,
	base_date STRING,
	base_point DOUBLE,
	index_series STRING,
	sample_count DOUBLE,
	latest_close DOUBLE,
	return_1m DOUBLE,
	asset_class STRING,
	index_hotspot STRING,
	currency STRING,
	is_cooperation STRING,
	has_tracking_product STRING,
	compliance_status STRING,
	index_category STRING,
	publish_date STRING,
	import_time TIMESTAMP
) timestamp(import_time) PARTITION BY MONTH;

CREATE TABLE 'index_daily_basic' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	total_mv DOUBLE,
	float_mv DOUBLE,
	total_share DOUBLE,
	float_share DOUBLE,
	free_share DOUBLE,
	turnover_rate DOUBLE,
	turnover_rate_f DOUBLE,
	pe DOUBLE,
	pe_ttm DOUBLE,
	pb DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'index_daily_market' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	close DOUBLE,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	pre_close DOUBLE,
	change DOUBLE,
	pct_chg DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'index_daily_market_backup_20260928_174208_752144' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	close DOUBLE,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	pre_close DOUBLE,
	change DOUBLE,
	pct_chg DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'index_member' ( 
	index_code SYMBOL,
	ts_code SYMBOL,
	update_time TIMESTAMP,
	index_name STRING,
	con_code STRING,
	con_name STRING,
	in_date STRING,
	out_date STRING,
	is_new SYMBOL,
	weight DOUBLE,
	level STRING,
	l1_name STRING,
	l2_name STRING,
	l3_name STRING
) timestamp(update_time) PARTITION BY YEAR;

CREATE TABLE 'index_monthly' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	close DOUBLE,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	pre_close DOUBLE,
	change DOUBLE,
	pct_chg DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	layer SYMBOL,
	bucket SYMBOL,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR;

CREATE TABLE 'index_weight' ( 
	index_code SYMBOL,
	con_code SYMBOL,
	trade_date TIMESTAMP,
	index_name STRING,
	index_name_en STRING,
	con_name STRING,
	con_name_en STRING,
	exchange SYMBOL,
	exchange_en STRING,
	weight DOUBLE,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(index_code,con_code,trade_date);

CREATE TABLE 'index_weight_backup_20260928_174248_245924' ( 
	index_code SYMBOL,
	con_code SYMBOL,
	trade_date TIMESTAMP,
	index_name STRING,
	index_name_en STRING,
	con_name STRING,
	con_name_en STRING,
	exchange SYMBOL,
	exchange_en STRING,
	weight DOUBLE,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(index_code,con_code,trade_date);

CREATE TABLE 'industry_intrinsic_valuation_state' ( 
	valuation_date TIMESTAMP,
	industry_l2 SYMBOL,
	route_id SYMBOL,
	industry_intrinsic_upside DOUBLE,
	industry_historical_percentile DOUBLE,
	industry_peer_percentile DOUBLE,
	industry_valuation_dispersion DOUBLE,
	industry_coverage DOUBLE,
	industry_confidence DOUBLE,
	company_count INT,
	covered_company_count INT,
	industry_pe_ttm DOUBLE,
	industry_pb DOUBLE,
	pe_historical_percentile DOUBLE,
	pb_historical_percentile DOUBLE,
	pe_peer_percentile DOUBLE,
	pb_peer_percentile DOUBLE,
	valuation_state SYMBOL,
	route_version SYMBOL,
	assumption_version SYMBOL,
	run_id SYMBOL
) timestamp(valuation_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(valuation_date,industry_l2,route_version,assumption_version);

CREATE TABLE 'industry_valuation_materialization_run' ( 
	created_at TIMESTAMP,
	run_id SYMBOL,
	status SYMBOL,
	start_date TIMESTAMP,
	end_date TIMESTAMP,
	product_id SYMBOL,
	package_id SYMBOL,
	input_digest STRING,
	schema_digest STRING,
	route_version SYMBOL,
	assumption_version SYMBOL,
	model_rows LONG,
	consensus_rows LONG,
	industry_rows LONG,
	conflict_rows LONG
) timestamp(created_at) PARTITION BY MONTH
DEDUP UPSERT KEYS(created_at,run_id);

CREATE TABLE 'industry_valuation_membership_conflict' ( 
	valuation_date TIMESTAMP,
	ts_code SYMBOL,
	active_industries INT,
	route_version SYMBOL,
	run_id SYMBOL
) timestamp(valuation_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(valuation_date,ts_code,route_version);

CREATE TABLE 'industry_valuation_route_assignment' ( 
	effective_from TIMESTAMP,
	industry_l2 SYMBOL,
	route_id SYMBOL,
	forecast_style SYMBOL,
	model_id SYMBOL,
	model_weight DOUBLE,
	route_version SYMBOL,
	assumption_version SYMBOL,
	run_id SYMBOL
) timestamp(effective_from) PARTITION BY MONTH
DEDUP UPSERT KEYS(effective_from,industry_l2,model_id,route_version);

CREATE TABLE 'l2_daily_features' ( 
	ts TIMESTAMP,
	symbol SYMBOL,
	has_deal BOOLEAN,
	has_order BOOLEAN,
	has_snapshot BOOLEAN,
	deal_records LONG,
	order_records LONG,
	snapshot_records LONG,
	continuous_auction_ratio DOUBLE,
	crossed_quote_ratio DOUBLE,
	locked_quote_ratio DOUBLE,
	missing_top5_quote_ratio DOUBLE,
	feature_version STRING,
	parser_version STRING,
	order_link_coverage DOUBLE,
	deal_order_match_ratio DOUBLE,
	nonnegative_depth_ratio DOUBLE,
	valid_spread_ratio DOUBLE,
	orphan_execution_ratio DOUBLE,
	cancel_without_fill_ratio DOUBLE,
	partial_fill_ratio DOUBLE,
	median_cancel_time_ms DOUBLE,
	median_first_fill_time_ms DOUBLE,
	passive_fill_ratio DOUBLE,
	large_order_fill_rate DOUBLE,
	partial_cancel_ratio DOUBLE,
	replace_like_ratio DOUBLE,
	open_30m_spread_mean DOUBLE,
	close_30m_obi_mean DOUBLE,
	midday_liquidity_drop DOUBLE,
	afternoon_ofi_reversal DOUBLE,
	open_close_vol_ratio DOUBLE,
	close_30m_impact_mean DOUBLE,
	aggressor_label_match_ratio DOUBLE,
	mid_cross_match_ratio DOUBLE,
	tick_rule_fallback_ratio DOUBLE,
	unclassified_trade_ratio DOUBLE,
	depth_recovery_5s DOUBLE,
	best_quote_depletion_rate DOUBLE,
	add_cancel_execute_ratio_top1 DOUBLE,
	depth_turnover_top5 DOUBLE,
	book_pressure_decay DOUBLE,
	queue_depletion_speed DOUBLE,
	mean_rel_aggro DOUBLE,
	median_inter_arrival_ms DOUBLE,
	algo_windows LONG,
	total_windows LONG,
	mean_algo_entropy DOUBLE,
	mean_retail_entropy DOUBLE,
	algo_total_amount DOUBLE,
	retail_total_amount DOUBLE,
	wash_trade_ratio DOUBLE,
	wash_amount DOUBLE,
	clean_amount DOUBLE,
	spoof_count LONG,
	fake_pressure_count LONG,
	fake_support_count LONG,
	mean_sell_otr DOUBLE,
	mean_buy_otr DOUBLE,
	mean_cancel_distance_ticks DOUBLE,
	total_net_flow DOUBLE,
	q2_accumulation DOUBLE,
	mean_vwap_skew DOUBLE,
	q1_count LONG,
	q2_count LONG,
	q3_count LONG,
	q4_count LONG,
	main_net_inflow DOUBLE,
	retail_funds_net_inflow DOUBLE,
	main_funds_buy_amount DOUBLE,
	main_funds_sell_amount DOUBLE,
	mid_tier_net_inflow DOUBLE,
	trade_size_gini DOUBLE,
	open_auction_net_inflow DOUBLE,
	close_auction_net_inflow DOUBLE,
	mean_spread DOUBLE,
	mean_obi_top5 DOUBLE,
	amihud_illiquidity DOUBLE,
	obi_top1_mean DOUBLE,
	obi_top5_std DOUBLE,
	obi_top1_std DOUBLE,
	depth_top1_mean DOUBLE,
	depth_top5_mean DOUBLE,
	depth_slope DOUBLE,
	depth_convexity DOUBLE,
	microprice_minus_mid_mean DOUBLE,
	microprice_minus_mid_std DOUBLE,
	effective_spread_mean DOUBLE,
	realized_spread_1m DOUBLE,
	impact_30s DOUBLE,
	impact_5m DOUBLE,
	orderbook_replenish_speed DOUBLE,
	ofi_mean DOUBLE,
	ofi_std DOUBLE,
	ofi_persistence DOUBLE,
	ofi_positive_ratio DOUBLE,
	ofi_negative_ratio DOUBLE,
	near_touch_cancel_add_ratio DOUBLE,
	gmm_main_force_ratio DOUBLE,
	gmm_hft_ratio DOUBLE,
	gmm_retail_ratio DOUBLE,
	gmm_main_force_net_inflow DOUBLE,
	mfi_pulse DOUBLE,
	mfi_escort DOUBLE,
	mfi_precip DOUBLE,
	mfi_score DOUBLE,
	ofi_slope DOUBLE,
	total_records LONG,
	clean_records LONG,
	large_order_records LONG
) timestamp(ts) PARTITION BY DAY
DEDUP UPSERT KEYS(ts,symbol);

CREATE TABLE 'l2_dataset_manifest' ( 
	trade_date STRING,
	symbol SYMBOL,
	market STRING,
	board STRING,
	source_root STRING,
	output_root STRING,
	feature_version STRING,
	daily_feature_ok BOOLEAN,
	t0_ok BOOLEAN,
	raw_row_counts STRING,
	output_paths STRING,
	cost_config STRING,
	horizons_min STRING,
	errors STRING,
	batch_id LONG,
	trade_date_ts TIMESTAMP
) timestamp(trade_date_ts) PARTITION BY DAY
DEDUP UPSERT KEYS(symbol,batch_id,trade_date_ts);

CREATE TABLE 'l2_dataset_manifest_backup_20260928_195756_898458' ( 
	trade_date STRING,
	symbol SYMBOL,
	market STRING,
	board STRING,
	source_root STRING,
	output_root STRING,
	feature_version STRING,
	daily_feature_ok BOOLEAN,
	t0_ok BOOLEAN,
	raw_row_counts STRING,
	output_paths STRING,
	cost_config STRING,
	horizons_min STRING,
	errors STRING,
	batch_id LONG,
	trade_date_ts TIMESTAMP
) timestamp(trade_date_ts) PARTITION BY DAY
DEDUP UPSERT KEYS(symbol,batch_id,trade_date_ts);

CREATE TABLE 'l2_event_response_features' ( 
	trade_date STRING,
	symbol SYMBOL,
	market STRING,
	board STRING,
	minute TIMESTAMP,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	volume DOUBLE,
	amount DOUBLE,
	tick_count LONG,
	active_buy_amount DOUBLE,
	active_sell_amount DOUBLE,
	vwap DOUBLE,
	has_trade_1m LONG,
	bid1 DOUBLE,
	ask1 DOUBLE,
	mid DOUBLE,
	spread DOUBLE,
	microprice DOUBLE,
	bid_depth_1 DOUBLE,
	ask_depth_1 DOUBLE,
	depth_1 DOUBLE,
	obi_1 DOUBLE,
	bid_depth_5 DOUBLE,
	ask_depth_5 DOUBLE,
	depth_5 DOUBLE,
	obi_5 DOUBLE,
	bid_depth_10 DOUBLE,
	ask_depth_10 DOUBLE,
	depth_10 DOUBLE,
	obi_10 DOUBLE,
	quote_count DOUBLE,
	ofi_1m DOUBLE,
	active_buy_ratio DOUBLE,
	active_sell_ratio DOUBLE,
	vwap_gap_to_mid DOUBLE,
	vwap_gap_to_open DOUBLE,
	vwap_slope_3m DOUBLE,
	vwap_slope_5m DOUBLE,
	ret_1m DOUBLE,
	vol_ratio_1m DOUBLE,
	range_1m DOUBLE,
	ret_3m DOUBLE,
	vol_ratio_3m DOUBLE,
	range_3m DOUBLE,
	ret_5m DOUBLE,
	vol_ratio_5m DOUBLE,
	range_5m DOUBLE,
	ret_10m DOUBLE,
	vol_ratio_10m DOUBLE,
	range_10m DOUBLE,
	ret_15m DOUBLE,
	vol_ratio_15m DOUBLE,
	range_15m DOUBLE,
	ret_30m DOUBLE,
	vol_ratio_30m DOUBLE,
	range_30m DOUBLE,
	cancel_ratio DOUBLE,
	event_type STRING,
	future_vwap_return_1m DOUBLE,
	future_mid_return_1m DOUBLE,
	future_vwap_return_3m DOUBLE,
	future_mid_return_3m DOUBLE,
	future_vwap_return_5m DOUBLE,
	future_mid_return_5m DOUBLE,
	future_vwap_return_10m DOUBLE,
	future_mid_return_10m DOUBLE,
	future_vwap_return_15m DOUBLE,
	future_mid_return_15m DOUBLE,
	future_vwap_return_30m DOUBLE,
	future_mid_return_30m DOUBLE
) timestamp(minute) PARTITION BY DAY
DEDUP UPSERT KEYS(symbol,minute,event_type);

CREATE TABLE 'l2_intraday_bar_features' ( 
	trade_date STRING,
	symbol SYMBOL,
	market STRING,
	board STRING,
	minute TIMESTAMP,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	volume DOUBLE,
	amount DOUBLE,
	tick_count LONG,
	active_buy_amount DOUBLE,
	active_sell_amount DOUBLE,
	vwap DOUBLE,
	has_trade_1m LONG,
	bid1 DOUBLE,
	ask1 DOUBLE,
	mid DOUBLE,
	spread DOUBLE,
	microprice DOUBLE,
	bid_depth_1 DOUBLE,
	ask_depth_1 DOUBLE,
	depth_1 DOUBLE,
	obi_1 DOUBLE,
	bid_depth_5 DOUBLE,
	ask_depth_5 DOUBLE,
	depth_5 DOUBLE,
	obi_5 DOUBLE,
	bid_depth_10 DOUBLE,
	ask_depth_10 DOUBLE,
	depth_10 DOUBLE,
	obi_10 DOUBLE,
	quote_count DOUBLE,
	ofi_1m DOUBLE,
	active_buy_ratio DOUBLE,
	active_sell_ratio DOUBLE,
	vwap_gap_to_mid DOUBLE,
	vwap_gap_to_open DOUBLE,
	vwap_slope_3m DOUBLE,
	vwap_slope_5m DOUBLE,
	ret_1m DOUBLE,
	vol_ratio_1m DOUBLE,
	range_1m DOUBLE,
	ret_3m DOUBLE,
	vol_ratio_3m DOUBLE,
	range_3m DOUBLE,
	ret_5m DOUBLE,
	vol_ratio_5m DOUBLE,
	range_5m DOUBLE,
	ret_10m DOUBLE,
	vol_ratio_10m DOUBLE,
	range_10m DOUBLE,
	ret_15m DOUBLE,
	vol_ratio_15m DOUBLE,
	range_15m DOUBLE,
	ret_30m DOUBLE,
	vol_ratio_30m DOUBLE,
	range_30m DOUBLE,
	cancel_ratio DOUBLE
) timestamp(minute) PARTITION BY DAY
DEDUP UPSERT KEYS(symbol,minute);

CREATE TABLE 'l2_live_gateway_stats' ( 
	actor_id SYMBOL,
	connection_state SYMBOL,
	active_symbols INT,
	active_topics INT,
	queue_size INT,
	total_received LONG,
	total_processed LONG,
	parse_errors LONG,
	failed_count LONG,
	last_receive_time TIMESTAMP,
	last_processed_time TIMESTAMP,
	channel_counts STRING,
	phase_counts STRING,
	log_path STRING,
	raw_root STRING,
	updated_at TIMESTAMP
) timestamp(updated_at) PARTITION BY DAY;

CREATE TABLE 'l2_live_gateway_stats_backup_20260928_184339_235985' ( 
	actor_id SYMBOL,
	connection_state SYMBOL,
	active_symbols INT,
	active_topics INT,
	queue_size INT,
	total_received LONG,
	total_processed LONG,
	parse_errors LONG,
	failed_count LONG,
	last_receive_time TIMESTAMP,
	last_processed_time TIMESTAMP,
	channel_counts STRING,
	phase_counts STRING,
	log_path STRING,
	raw_root STRING,
	updated_at TIMESTAMP
) timestamp(updated_at) PARTITION BY DAY;

CREATE TABLE 'l2_live_micro_state_1m' ( 
	symbol SYMBOL,
	trade_minute TIMESTAMP,
	trade_date STRING,
	source SYMBOL,
	data_mode SYMBOL,
	trade_count_1m INT,
	last_price DOUBLE,
	mid_price DOUBLE,
	open_1m DOUBLE,
	high_1m DOUBLE,
	low_1m DOUBLE,
	close_1m DOUBLE,
	ret_1m DOUBLE,
	ret_3m DOUBLE,
	ret_5m DOUBLE,
	vwap_1m DOUBLE,
	vwap_gap_bps DOUBLE,
	amount_1m DOUBLE,
	volume_1m DOUBLE,
	data_delay_ms DOUBLE,
	spread_bps DOUBLE,
	bid_price_1 DOUBLE,
	bid_price_2 DOUBLE,
	bid_price_3 DOUBLE,
	bid_price_4 DOUBLE,
	bid_price_5 DOUBLE,
	bid_price_6 DOUBLE,
	bid_price_7 DOUBLE,
	bid_price_8 DOUBLE,
	bid_price_9 DOUBLE,
	bid_price_10 DOUBLE,
	ask_price_1 DOUBLE,
	ask_price_2 DOUBLE,
	ask_price_3 DOUBLE,
	ask_price_4 DOUBLE,
	ask_price_5 DOUBLE,
	ask_price_6 DOUBLE,
	ask_price_7 DOUBLE,
	ask_price_8 DOUBLE,
	ask_price_9 DOUBLE,
	ask_price_10 DOUBLE,
	bid_vol_1 DOUBLE,
	bid_vol_2 DOUBLE,
	bid_vol_3 DOUBLE,
	bid_vol_4 DOUBLE,
	bid_vol_5 DOUBLE,
	bid_vol_6 DOUBLE,
	bid_vol_7 DOUBLE,
	bid_vol_8 DOUBLE,
	bid_vol_9 DOUBLE,
	bid_vol_10 DOUBLE,
	ask_vol_1 DOUBLE,
	ask_vol_2 DOUBLE,
	ask_vol_3 DOUBLE,
	ask_vol_4 DOUBLE,
	ask_vol_5 DOUBLE,
	ask_vol_6 DOUBLE,
	ask_vol_7 DOUBLE,
	ask_vol_8 DOUBLE,
	ask_vol_9 DOUBLE,
	ask_vol_10 DOUBLE,
	depth_bid_top1 DOUBLE,
	depth_ask_top1 DOUBLE,
	depth_bid_top5 DOUBLE,
	depth_ask_top5 DOUBLE,
	depth_bid_top10 DOUBLE,
	depth_ask_top10 DOUBLE,
	obi_top1 DOUBLE,
	obi_top5 DOUBLE,
	obi_top10 DOUBLE,
	microprice DOUBLE,
	microprice_gap_bps DOUBLE,
	bid_wall_score DOUBLE,
	ask_wall_score DOUBLE,
	depth_depletion_score DOUBLE,
	depth_recovery_score DOUBLE,
	active_buy_amount_1m DOUBLE,
	active_sell_amount_1m DOUBLE,
	active_buy_volume_1m DOUBLE,
	active_sell_volume_1m DOUBLE,
	active_buy_ratio DOUBLE,
	active_sell_ratio DOUBLE,
	large_buy_amount_1m DOUBLE,
	large_sell_amount_1m DOUBLE,
	large_trade_imbalance DOUBLE,
	price_impact_bps DOUBLE,
	trade_intensity_zscore DOUBLE,
	vol_ratio_1m DOUBLE,
	near_bid_add_amount DOUBLE,
	near_ask_add_amount DOUBLE,
	near_bid_cancel_amount DOUBLE,
	near_ask_cancel_amount DOUBLE,
	bid_cancel_add_ratio DOUBLE,
	ask_cancel_add_ratio DOUBLE,
	fake_bid_support_score DOUBLE,
	fake_ask_pressure_score DOUBLE,
	queue_consume_bid_score DOUBLE,
	queue_consume_ask_score DOUBLE,
	no_trade_score DOUBLE,
	buy_chase_risk_score DOUBLE,
	buy_support_score DOUBLE,
	sell_pressure_score DOUBLE,
	sell_exhaustion_score DOUBLE,
	passive_grid_score DOUBLE,
	micro_action SYMBOL,
	action_reason STRING,
	updated_at TIMESTAMP
) timestamp(trade_minute) PARTITION BY DAY;

CREATE TABLE 'l2_live_normalized_events' ( 
	vendor SYMBOL,
	recv_ts TIMESTAMP,
	trade_date STRING,
	exchange_ts TIMESTAMP,
	symbol SYMBOL,
	exchange SYMBOL,
	channel SYMBOL,
	session_phase SYMBOL,
	raw_topic SYMBOL,
	raw_payload STRING,
	parse_status SYMBOL,
	parser_version SYMBOL,
	event_type SYMBOL,
	aggressor_side SYMBOL,
	bs_flag SYMBOL,
	order_type SYMBOL,
	order_side SYMBOL,
	volume_semantics SYMBOL,
	kuake_order_type INT,
	price DOUBLE,
	volume LONG,
	amount DOUBLE,
	sub_seq LONG,
	channel_no STRING,
	order_id STRING,
	sell_order_id STRING,
	buy_order_id STRING,
	total_volume LONG,
	total_amount LONG,
	trade_count LONG,
	bid_price_1 DOUBLE,
	bid_volume_1 LONG,
	ask_price_1 DOUBLE,
	ask_volume_1 LONG,
	spread DOUBLE,
	mid_price DOUBLE,
	depth_top1 LONG,
	obi_top1 DOUBLE,
	updated_at TIMESTAMP
) timestamp(recv_ts) PARTITION BY DAY;

CREATE TABLE 'l2_live_subscription_events' ( 
	topic SYMBOL,
	symbol SYMBOL,
	channel SYMBOL,
	action SYMBOL,
	status SYMBOL,
	mid INT,
	error STRING,
	updated_at TIMESTAMP
) timestamp(updated_at) PARTITION BY DAY;

CREATE TABLE 'l2_live_subscription_intents' ( 
	symbol SYMBOL,
	channel SYMBOL,
	priority INT,
	source SYMBOL,
	reason STRING,
	enabled BOOLEAN,
	expires_at TIMESTAMP,
	updated_at TIMESTAMP
) timestamp(updated_at) PARTITION BY DAY;

CREATE TABLE 'l2_t0_training_labels' ( 
	trade_date STRING,
	symbol SYMBOL,
	market STRING,
	board STRING,
	minute TIMESTAMP,
	executability_label LONG,
	executability_reason STRING,
	policy_label STRING,
	policy_reason STRING,
	primary_t0_side STRING,
	roundtrip_cost_rate DOUBLE,
	stamp_tax_rate DOUBLE,
	commission_rate DOUBLE,
	slippage_bps DOUBLE,
	future_vwap_return_1m DOUBLE,
	future_mid_return_1m DOUBLE,
	sell_first_gross_alpha_1m DOUBLE,
	sell_first_net_alpha_1m DOUBLE,
	sell_first_opportunity_label_1m LONG,
	buy_first_aux_gross_alpha_1m DOUBLE,
	buy_first_aux_net_alpha_1m DOUBLE,
	buy_first_aux_opportunity_label_1m LONG,
	future_vwap_return_3m DOUBLE,
	future_mid_return_3m DOUBLE,
	sell_first_gross_alpha_3m DOUBLE,
	sell_first_net_alpha_3m DOUBLE,
	sell_first_opportunity_label_3m LONG,
	buy_first_aux_gross_alpha_3m DOUBLE,
	buy_first_aux_net_alpha_3m DOUBLE,
	buy_first_aux_opportunity_label_3m LONG,
	future_vwap_return_5m DOUBLE,
	future_mid_return_5m DOUBLE,
	sell_first_gross_alpha_5m DOUBLE,
	sell_first_net_alpha_5m DOUBLE,
	sell_first_opportunity_label_5m LONG,
	buy_first_aux_gross_alpha_5m DOUBLE,
	buy_first_aux_net_alpha_5m DOUBLE,
	buy_first_aux_opportunity_label_5m LONG,
	future_vwap_return_10m DOUBLE,
	future_mid_return_10m DOUBLE,
	sell_first_gross_alpha_10m DOUBLE,
	sell_first_net_alpha_10m DOUBLE,
	sell_first_opportunity_label_10m LONG,
	buy_first_aux_gross_alpha_10m DOUBLE,
	buy_first_aux_net_alpha_10m DOUBLE,
	buy_first_aux_opportunity_label_10m LONG,
	future_vwap_return_15m DOUBLE,
	future_mid_return_15m DOUBLE,
	sell_first_gross_alpha_15m DOUBLE,
	sell_first_net_alpha_15m DOUBLE,
	sell_first_opportunity_label_15m LONG,
	buy_first_aux_gross_alpha_15m DOUBLE,
	buy_first_aux_net_alpha_15m DOUBLE,
	buy_first_aux_opportunity_label_15m LONG,
	future_vwap_return_30m DOUBLE,
	future_mid_return_30m DOUBLE,
	sell_first_gross_alpha_30m DOUBLE,
	sell_first_net_alpha_30m DOUBLE,
	sell_first_opportunity_label_30m LONG,
	buy_first_aux_gross_alpha_30m DOUBLE,
	buy_first_aux_net_alpha_30m DOUBLE,
	buy_first_aux_opportunity_label_30m LONG
) timestamp(minute) PARTITION BY DAY
DEDUP UPSERT KEYS(symbol,minute);

CREATE TABLE 'macro_core_monthly' ( 
	month TIMESTAMP,
	cpi_yoy DOUBLE,
	ppi_yoy DOUBLE,
	pmi_mfg DOUBLE,
	gdp_yoy DOUBLE,
	m2_yoy DOUBLE,
	social_financing_stock DOUBLE,
	new_rmb_loan DOUBLE,
	social_financing_yoy DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'macro_liquidity_credit_monthly' ( 
	month TIMESTAMP,
	rmb_index_month_end DOUBLE,
	rmb_index_ret_1m DOUBLE,
	shibor_on_month_avg DOUBLE,
	shibor_1w_month_avg DOUBLE,
	shibor_3m_month_avg DOUBLE,
	shibor_1y_month_avg DOUBLE,
	lpr_1y_month_end DOUBLE,
	lpr_5y_month_end DOUBLE,
	shibor_slope_1w_3m_month_avg DOUBLE,
	shibor_slope_3m_1y_month_avg DOUBLE,
	gov_3y_month_end DOUBLE,
	gov_5y_month_end DOUBLE,
	gov_7y_month_end DOUBLE,
	gov_10y_month_end DOUBLE,
	aaa_mtn_3y_month_end DOUBLE,
	aaa_mtn_5y_month_end DOUBLE,
	aaa_mtn_7y_month_end DOUBLE,
	aaa_mtn_10y_month_end DOUBLE,
	aaa_bank_3y_month_end DOUBLE,
	aaa_bank_5y_month_end DOUBLE,
	aaa_bank_7y_month_end DOUBLE,
	aaa_bank_10y_month_end DOUBLE,
	credit_spread_aaa_gov_3y DOUBLE,
	credit_spread_aaa_gov_5y DOUBLE,
	credit_spread_aaa_gov_7y DOUBLE,
	credit_spread_aaa_gov_10y DOUBLE,
	spread_aaa_bank_gov_3y DOUBLE,
	spread_aaa_bank_gov_5y DOUBLE,
	spread_aaa_bank_gov_7y DOUBLE,
	spread_aaa_bank_gov_10y DOUBLE,
	spread_bank_mtn_3y DOUBLE,
	spread_bank_mtn_5y DOUBLE,
	spread_bank_mtn_7y DOUBLE,
	spread_bank_mtn_10y DOUBLE,
	term_spread DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'margin_all' ( 
	trade_date TIMESTAMP,
	exchange_id SYMBOL,
	rzye DOUBLE,
	rzmre DOUBLE,
	rzche DOUBLE,
	rqye DOUBLE,
	rqmcl DOUBLE,
	rzrqye DOUBLE,
	rqyl DOUBLE
) timestamp(trade_date) PARTITION BY YEAR;

CREATE TABLE 'margin_detail' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	name STRING,
	rzye DOUBLE,
	rzmre DOUBLE,
	rzche DOUBLE,
	rqye DOUBLE,
	rqyl DOUBLE,
	rqchl DOUBLE,
	rqmcl DOUBLE,
	rzrqye DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(trade_date,ts_code);

CREATE TABLE 'margin_secs' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	name STRING,
	exchange SYMBOL
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(trade_date,ts_code);

CREATE TABLE 'margin_secs_backup_20260928_181253_876146' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	name STRING,
	exchange SYMBOL
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(trade_date,ts_code);

CREATE TABLE 'margin_trading' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	rzye DOUBLE,
	rzmre DOUBLE,
	rzche DOUBLE,
	rqyl DOUBLE,
	rqmcl DOUBLE,
	rqchl DOUBLE,
	rqye DOUBLE,
	rzrqye DOUBLE
) timestamp(trade_date) PARTITION BY YEAR;

CREATE TABLE 'margin_zrz' ( 
	trade_date TIMESTAMP,
	ob DOUBLE,
	auc_amount DOUBLE,
	repo_amount DOUBLE,
	repay_amount DOUBLE,
	cb DOUBLE
) timestamp(trade_date) PARTITION BY YEAR;

CREATE TABLE 'market_barometer_cache_coverage' ( 
	trade_date TIMESTAMP,
	dataset_id SYMBOL,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,dataset_id,source_version);

CREATE TABLE 'market_breadth_daily_cache' ( 
	trade_date TIMESTAMP,
	stock_count LONG,
	up_count LONG,
	down_count LONG,
	flat_count LONG,
	avg_pct_change DOUBLE,
	total_amount_yi DOUBLE,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'market_breadth_monthly' ( 
	month TIMESTAMP,
	avg_up_count DOUBLE,
	avg_down_count DOUBLE,
	avg_flat_count DOUBLE,
	avg_up_down_ratio DOUBLE,
	avg_limit_up_count DOUBLE,
	avg_limit_down_count DOUBLE,
	month_total_amount DOUBLE,
	avg_turnover_rate DOUBLE,
	median_turnover_rate DOUBLE,
	avg_pct_positive_ratio DOUBLE,
	avg_pct_negative_ratio DOUBLE,
	tradable_stock_count_month_end DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'market_sentiment_daily' ( 
	trade_date TIMESTAMP,
	month SYMBOL,
	sentiment_score DOUBLE,
	sentiment_score_core DOUBLE,
	sentiment_score_enhanced DOUBLE,
	sentiment_state SYMBOL,
	heat_score DOUBLE,
	breadth_score DOUBLE,
	limit_score DOUBLE,
	profit_effect_score DOUBLE,
	leverage_score DOUBLE,
	moneyflow_score DOUBLE,
	structure_score DOUBLE,
	divergence_score DOUBLE,
	pc1_heat DOUBLE,
	pc2_divergence DOUBLE,
	pc3_structure DOUBLE,
	total_amount DOUBLE,
	amount_to_circ_mv DOUBLE,
	avg_turnover_rate DOUBLE,
	median_turnover_rate DOUBLE,
	high_turnover_stock_ratio DOUBLE,
	top_amount_share DOUBLE,
	up_ratio DOUBLE,
	down_ratio DOUBLE,
	up_down_spread DOUBLE,
	limit_up_ratio DOUBLE,
	limit_down_ratio DOUBLE,
	limit_net_ratio DOUBLE,
	max_limit_streak DOUBLE,
	limit_promotion_rate DOUBLE,
	pct_above_ma20 DOUBLE,
	median_distance_to_ma20 DOUBLE,
	pct_distance_to_ma20_gt_5 DOUBLE,
	pct_distance_to_ma20_lt_minus_5 DOUBLE,
	small_large_ret DOUBLE,
	growth_value_ret DOUBLE,
	margin_buy_sell_ratio DOUBLE,
	margin_buy_amount_ratio DOUBLE,
	margin_balance_change_5d DOUBLE,
	moneyflow_net_amount_ratio DOUBLE,
	moneyflow_large_net_ratio DOUBLE,
	index_up_breadth_down BOOLEAN,
	amount_up_limit_down BOOLEAN,
	small_up_large_down BOOLEAN,
	sentiment_up_return_down BOOLEAN,
	is_false_boom BOOLEAN,
	is_limit_collapse BOOLEAN,
	is_leverage_warning BOOLEAN,
	is_crowded BOOLEAN,
	data_quality_flag SYMBOL,
	model_version SYMBOL,
	updated_at TIMESTAMP
) timestamp(trade_date) PARTITION BY MONTH;

CREATE TABLE 'moneyflow' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	buy_sm_vol LONG,
	buy_sm_amount DOUBLE,
	sell_sm_vol LONG,
	sell_sm_amount DOUBLE,
	buy_md_vol LONG,
	buy_md_amount DOUBLE,
	sell_md_vol LONG,
	sell_md_amount DOUBLE,
	buy_lg_vol LONG,
	buy_lg_amount DOUBLE,
	sell_lg_vol LONG,
	sell_lg_amount DOUBLE,
	buy_elg_vol LONG,
	buy_elg_amount DOUBLE,
	sell_elg_vol LONG,
	sell_elg_amount DOUBLE,
	net_mf_vol LONG,
	net_mf_amount DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'moneyflow_backup_20260928_184806_272189' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	buy_sm_vol LONG,
	buy_sm_amount DOUBLE,
	sell_sm_vol LONG,
	sell_sm_amount DOUBLE,
	buy_md_vol LONG,
	buy_md_amount DOUBLE,
	sell_md_vol LONG,
	sell_md_amount DOUBLE,
	buy_lg_vol LONG,
	buy_lg_amount DOUBLE,
	sell_lg_vol LONG,
	sell_lg_amount DOUBLE,
	buy_elg_vol LONG,
	buy_elg_amount DOUBLE,
	sell_elg_vol LONG,
	sell_elg_amount DOUBLE,
	net_mf_vol LONG,
	net_mf_amount DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'moneyflow_dc' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	name STRING,
	pct_change DOUBLE,
	close DOUBLE,
	net_amount DOUBLE,
	net_amount_rate DOUBLE,
	buy_elg_amount DOUBLE,
	buy_elg_amount_rate DOUBLE,
	buy_lg_amount DOUBLE,
	buy_lg_amount_rate DOUBLE,
	buy_md_amount DOUBLE,
	buy_md_amount_rate DOUBLE,
	buy_sm_amount DOUBLE,
	buy_sm_amount_rate DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'moneyflow_hsgt' ( 
	trade_date TIMESTAMP,
	ggt_ss DOUBLE,
	ggt_sz DOUBLE,
	hgt DOUBLE,
	sgt DOUBLE,
	north_money DOUBLE,
	south_money DOUBLE
) timestamp(trade_date) PARTITION BY DAY;

CREATE TABLE 'moneyflow_hsgt_backup_20260928_182500_103692' ( 
	trade_date TIMESTAMP,
	ggt_ss DOUBLE,
	ggt_sz DOUBLE,
	hgt DOUBLE,
	sgt DOUBLE,
	north_money DOUBLE,
	south_money DOUBLE
) timestamp(trade_date) PARTITION BY DAY;

CREATE TABLE 'moneyflow_ths' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	name STRING,
	pct_change DOUBLE,
	latest DOUBLE,
	net_amount DOUBLE,
	net_d5_amount DOUBLE,
	buy_lg_amount DOUBLE,
	buy_lg_amount_rate DOUBLE,
	buy_md_amount DOUBLE,
	buy_md_amount_rate DOUBLE,
	buy_sm_amount DOUBLE,
	buy_sm_amount_rate DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'moneyflow_ths_backup_20260928_181457_424762' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	name STRING,
	pct_change DOUBLE,
	latest DOUBLE,
	net_amount DOUBLE,
	net_d5_amount DOUBLE,
	buy_lg_amount DOUBLE,
	buy_lg_amount_rate DOUBLE,
	buy_md_amount DOUBLE,
	buy_md_amount_rate DOUBLE,
	buy_sm_amount DOUBLE,
	buy_sm_amount_rate DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'stk_factor' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	close DOUBLE,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	pre_close DOUBLE,
	change DOUBLE,
	pct_change DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	open_hfq DOUBLE,
	open_qfq DOUBLE,
	close_hfq DOUBLE,
	close_qfq DOUBLE,
	high_hfq DOUBLE,
	high_qfq DOUBLE,
	low_hfq DOUBLE,
	low_qfq DOUBLE,
	pre_close_hfq DOUBLE,
	pre_close_qfq DOUBLE,
	macd_dif DOUBLE,
	macd_dea DOUBLE,
	macd DOUBLE,
	kdj_k DOUBLE,
	kdj_d DOUBLE,
	kdj_j DOUBLE,
	rsi_6 DOUBLE,
	rsi_12 DOUBLE,
	rsi_24 DOUBLE,
	boll_upper DOUBLE,
	boll_mid DOUBLE,
	boll_lower DOUBLE,
	cci DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE MATERIALIZED VIEW 'mv_market_breadth_daily_v1' WITH BASE 'stk_factor' REFRESH EVERY 1m START '2026-09-18T15:32:22.866888Z' AS (
SELECT
    trade_date,
    count() AS stock_count,
    sum(CASE WHEN pct_change > 0 THEN 1 ELSE 0 END) AS up_count,
    sum(CASE WHEN pct_change < 0 THEN 1 ELSE 0 END) AS down_count,
    sum(CASE WHEN pct_change = 0 THEN 1 ELSE 0 END) AS flat_count,
    avg(pct_change) AS avg_pct_change,
    sum(amount) / 100000.0 AS total_amount_yi
FROM stk_factor

SAMPLE BY 1d ALIGN TO CALENDAR
) PARTITION BY MONTH;

CREATE MATERIALIZED VIEW 'mv_retail_sentiment_daily_v1' WITH BASE 'l2_daily_features' REFRESH EVERY 1m START '2026-09-18T15:32:22.955712Z' AS (
SELECT
    ts AS trade_date,
    avg(gmm_retail_ratio) AS avg_retail_ratio,
    avg(mean_retail_entropy) AS avg_retail_entropy,
    sum(retail_total_amount) / 100000000.0 AS total_retail_amount_yi,
    sum(retail_funds_net_inflow) / 100000000.0 AS total_retail_net_inflow_yi,
    avg(mean_rel_aggro) AS avg_rel_aggro,
    sum(q1_count) AS total_q1,
    sum(q3_count) AS total_q3,
    avg(wash_trade_ratio) AS avg_wash_trade_ratio,
    sum(spoof_count) AS total_spoof_count,
    sum(fake_support_count + fake_pressure_count) AS total_manipulation_count,
    avg(mfi_score) AS avg_mfi_score,
    sum(main_net_inflow) / 100000000.0 AS total_main_net_yi
FROM l2_daily_features

SAMPLE BY 1d ALIGN TO CALENDAR
) PARTITION BY MONTH;

CREATE TABLE 'order_fills' ( 
	fill_id SYMBOL,
	order_id SYMBOL,
	fill_price DOUBLE,
	fill_quantity INT,
	fill_time TIMESTAMP,
	fill_amount DOUBLE,
	commission DOUBLE,
	is_maker BOOLEAN
) timestamp(fill_time) PARTITION BY YEAR;

CREATE TABLE 'order_records' ( 
	record_id SYMBOL,
	ts_code SYMBOL,
	symbol STRING,
	stock_name STRING,
	side STRING,
	quantity INT,
	filled_quantity INT,
	price DOUBLE,
	amount DOUBLE,
	status STRING,
	account_name STRING,
	strategy_name STRING,
	broker_order_id STRING,
	signal_id STRING,
	order_type STRING,
	execution_mode STRING,
	source STRING,
	submitted_at TIMESTAMP,
	executed_at TIMESTAMP,
	created_at TIMESTAMP,
	note STRING,
	extra STRING,
	order_id STRING
) timestamp(submitted_at) PARTITION BY YEAR;

CREATE TABLE 'orders' ( 
	order_id SYMBOL,
	signal_id SYMBOL,
	parent_order_id SYMBOL,
	ts_code SYMBOL,
	side SYMBOL,
	order_type SYMBOL,
	price_type SYMBOL,
	quantity LONG,
	price DOUBLE,
	status SYMBOL,
	filled_quantity LONG,
	filled_amount DOUBLE,
	avg_price DOUBLE,
	commission DOUBLE,
	slippage DOUBLE,
	create_time TIMESTAMP,
	submit_time TIMESTAMP,
	fill_time TIMESTAMP,
	cancel_time TIMESTAMP,
	execution_mode SYMBOL,
	broker_order_id SYMBOL,
	strategy_name SYMBOL,
	order_remark STRING,
	risk_check_result STRING,
	reject_reason STRING
) timestamp(create_time) PARTITION BY YEAR
DEDUP UPSERT KEYS(create_time);

CREATE TABLE 'paired_execution_plans' ( 
	plan_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	account_key STRING,
	signal_date STRING,
	status STRING,
	review_mode STRING,
	reviewed_by STRING,
	regime STRING,
	regime_score DOUBLE,
	reason_codes STRING,
	execution_style STRING,
	old_basket STRING,
	new_basket STRING,
	spread_snapshot STRING,
	risk_limits STRING,
	legs STRING,
	signal_ids STRING,
	audit_reason STRING,
	expected_day_pnl DOUBLE,
	expected_shortfall DOUBLE,
	simulation_summary STRING,
	t0_overlay STRING,
	created_at TIMESTAMP,
	updated_at TIMESTAMP
) timestamp(updated_at) PARTITION BY YEAR;

CREATE TABLE 'pledge_stat' ( 
	ts_code SYMBOL,
	end_date TIMESTAMP,
	pledge_count LONG,
	unrest_pledge DOUBLE,
	rest_pledge DOUBLE,
	total_share DOUBLE,
	pledge_ratio DOUBLE
) timestamp(end_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,end_date);

CREATE TABLE 'pledge_stat_backup_20260928_181438_800771' ( 
	ts_code SYMBOL,
	end_date TIMESTAMP,
	pledge_count LONG,
	unrest_pledge DOUBLE,
	rest_pledge DOUBLE,
	total_share DOUBLE,
	pledge_ratio DOUBLE
) timestamp(end_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,end_date);

CREATE TABLE 'prediction_definition_v1' ( 
	registered_at TIMESTAMP,
	prediction_id SYMBOL,
	definition_version SYMBOL,
	name_zh STRING,
	name_en STRING,
	description_zh STRING,
	entity_type SYMBOL,
	frequency SYMBOL,
	horizon STRING,
	output_kind SYMBOL,
	model_id SYMBOL,
	model_version SYMBOL,
	lifecycle_status SYMBOL,
	owner SYMBOL,
	factor_dependencies STRING,
	used_by STRING,
	enabled BOOLEAN
) timestamp(registered_at) PARTITION BY YEAR
DEDUP UPSERT KEYS(registered_at,prediction_id,definition_version);

CREATE TABLE 'qmt_1m_bars' ( 
	ts_code SYMBOL,
	bar_time TIMESTAMP,
	period STRING,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	volume DOUBLE,
	amount DOUBLE,
	source STRING,
	updated_at TIMESTAMP
) timestamp(bar_time) PARTITION BY DAY
DEDUP UPSERT KEYS(ts_code,bar_time);

CREATE TABLE 'qmt_assets' ( 
	cash DOUBLE,
	frozen_cash DOUBLE,
	market_value DOUBLE,
	total_asset DOUBLE,
	account_id SYMBOL,
	query_time TIMESTAMP,
	broker_name SYMBOL
) timestamp(query_time) PARTITION BY YEAR
DEDUP UPSERT KEYS(account_id,query_time);

CREATE TABLE 'qmt_orders' ( 
	broker_order_id SYMBOL,
	ts_code SYMBOL,
	side STRING,
	quantity INT,
	price DOUBLE,
	filled_quantity INT,
	filled_amount DOUBLE,
	status STRING,
	status_name STRING,
	order_time TIMESTAMP,
	strategy_name STRING,
	order_remark STRING,
	stock_name STRING,
	account_id SYMBOL,
	query_time TIMESTAMP,
	broker_name SYMBOL
) timestamp(order_time) PARTITION BY YEAR
DEDUP UPSERT KEYS(broker_order_id,order_time,account_id);

CREATE TABLE 'qmt_positions' ( 
	ts_code SYMBOL,
	volume INT,
	available_volume INT,
	avg_price DOUBLE,
	market_value DOUBLE,
	profit DOUBLE,
	profit_ratio DOUBLE,
	current_price DOUBLE,
	stock_name STRING,
	account_id SYMBOL,
	query_time TIMESTAMP,
	broker_name SYMBOL
) timestamp(query_time) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,account_id,query_time);

CREATE TABLE 'qmt_tick_data' ( 
	tick_time TIMESTAMP,
	timetag STRING,
	lastPrice DOUBLE,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	lastClose DOUBLE,
	amount DOUBLE,
	volume INT,
	pvolume INT,
	stockStatus INT,
	openInt INT,
	settlementPrice DOUBLE,
	lastSettlementPrice DOUBLE,
	askPrice STRING,
	bidPrice STRING,
	askVol STRING,
	bidVol STRING,
	ts_code SYMBOL,
	time LONG
) timestamp(tick_time) PARTITION BY MONTH;

CREATE TABLE 'qmt_trades' ( 
	trade_id SYMBOL,
	broker_order_id SYMBOL,
	ts_code SYMBOL,
	side STRING,
	price DOUBLE,
	quantity INT,
	amount DOUBLE,
	trade_time TIMESTAMP,
	commission DOUBLE,
	stock_name STRING,
	account_id SYMBOL,
	query_time TIMESTAMP,
	broker_name SYMBOL
) timestamp(trade_time) PARTITION BY YEAR
DEDUP UPSERT KEYS(trade_id,trade_time,account_id);

CREATE TABLE 'regime_display_materialization_run' ( 
	started_at TIMESTAMP,
	run_id SYMBOL,
	metric_id SYMBOL,
	mode SYMBOL,
	requested_start_date TIMESTAMP,
	requested_end_date TIMESTAMP,
	source_as_of TIMESTAMP,
	run_status SYMBOL,
	quality_status SYMBOL,
	reason_code SYMBOL,
	reason_message STRING,
	rows_evaluated INT,
	rows_written INT,
	input_fingerprint STRING,
	method_version SYMBOL,
	completed_at TIMESTAMP
) timestamp(started_at) PARTITION BY MONTH
DEDUP UPSERT KEYS(started_at,run_id,metric_id);

CREATE TABLE 'regime_erp_source_status_daily' ( 
	evaluation_date TIMESTAMP,
	evaluated_at TIMESTAMP,
	source_id SYMBOL,
	index_id SYMBOL,
	valuation_metric SYMBOL,
	authorized BOOLEAN,
	source_available BOOLEAN,
	source_as_of TIMESTAMP,
	bond_as_of TIMESTAMP,
	quality_status SYMBOL,
	reason_code SYMBOL,
	reason_message STRING,
	method_version SYMBOL,
	source_lineage STRING,
	input_fingerprint STRING,
	run_id STRING
) timestamp(evaluation_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(evaluation_date,method_version);

CREATE TABLE 'regime_features_monitor_daily' ( 
	trade_date TIMESTAMP,
	month SYMBOL,
	hs300_ret_mtd DOUBLE,
	zz500_ret_mtd DOUBLE,
	all_a_ret_mtd DOUBLE,
	cs1000_ret_mtd DOUBLE,
	small_large_ret_mtd DOUBLE,
	growth_value_ret_mtd DOUBLE,
	avg_up_down_ratio_5d DOUBLE,
	avg_limit_up_count_5d DOUBLE,
	avg_limit_down_count_5d DOUBLE,
	avg_turnover_rate_5d DOUBLE,
	avg_pct_positive_ratio_5d DOUBLE,
	avg_pct_negative_ratio_5d DOUBLE,
	northbound_net_buy_mtd DOUBLE,
	margin_balance_change_mtd DOUBLE,
	all_a_pe_ttm_percentile_latest DOUBLE,
	all_a_pb_percentile_latest DOUBLE,
	erp_latest DOUBLE,
	data_quality_flag SYMBOL,
	updated_at TIMESTAMP
) timestamp(trade_date) PARTITION BY MONTH;

CREATE TABLE 'regime_features_monthly' ( 
	month TIMESTAMP,
	hs300_ret_1m DOUBLE,
	zz500_ret_1m DOUBLE,
	all_a_ret_1m DOUBLE,
	cs1000_ret_1m DOUBLE,
	small_large_ret_1m DOUBLE,
	mid_large_ret_1m DOUBLE,
	growth_ret_1m DOUBLE,
	value_ret_1m DOUBLE,
	growth_value_ret_1m DOUBLE,
	energy_ret_1m DOUBLE,
	materials_ret_1m DOUBLE,
	industrials_ret_1m DOUBLE,
	consumer_discretionary_ret_1m DOUBLE,
	consumer_staples_ret_1m DOUBLE,
	healthcare_ret_1m DOUBLE,
	financials_ret_1m DOUBLE,
	it_ret_1m DOUBLE,
	telecom_ret_1m DOUBLE,
	utilities_ret_1m DOUBLE,
	energy_vs_all_a_1m DOUBLE,
	materials_vs_all_a_1m DOUBLE,
	industrials_vs_all_a_1m DOUBLE,
	consumer_discretionary_vs_all_a_1m DOUBLE,
	consumer_staples_vs_all_a_1m DOUBLE,
	healthcare_vs_all_a_1m DOUBLE,
	financials_vs_all_a_1m DOUBLE,
	it_vs_all_a_1m DOUBLE,
	telecom_vs_all_a_1m DOUBLE,
	utilities_vs_all_a_1m DOUBLE,
	avg_up_count DOUBLE,
	avg_down_count DOUBLE,
	avg_flat_count DOUBLE,
	avg_up_down_ratio DOUBLE,
	avg_limit_up_count DOUBLE,
	avg_limit_down_count DOUBLE,
	month_total_amount DOUBLE,
	avg_turnover_rate DOUBLE,
	median_turnover_rate DOUBLE,
	avg_pct_positive_ratio DOUBLE,
	avg_pct_negative_ratio DOUBLE,
	tradable_stock_count_month_end DOUBLE,
	usdcnh_month_end DOUBLE,
	usdcnh_ret_1m DOUBLE,
	shibor_on_month_avg DOUBLE,
	shibor_1w_month_avg DOUBLE,
	shibor_3m_month_avg DOUBLE,
	shibor_1y_month_avg DOUBLE,
	lpr_1y_month_end DOUBLE,
	lpr_5y_month_end DOUBLE,
	shibor_slope_1w_3m_month_avg DOUBLE,
	shibor_slope_3m_1y_month_avg DOUBLE,
	gov_3y_month_end DOUBLE,
	gov_5y_month_end DOUBLE,
	gov_7y_month_end DOUBLE,
	gov_10y_month_end DOUBLE,
	aaa_mtn_3y_month_end DOUBLE,
	aaa_mtn_5y_month_end DOUBLE,
	aaa_mtn_7y_month_end DOUBLE,
	aaa_mtn_10y_month_end DOUBLE,
	aaa_bank_3y_month_end DOUBLE,
	aaa_bank_5y_month_end DOUBLE,
	aaa_bank_7y_month_end DOUBLE,
	aaa_bank_10y_month_end DOUBLE,
	credit_spread_aaa_gov_3y DOUBLE,
	credit_spread_aaa_gov_5y DOUBLE,
	credit_spread_aaa_gov_7y DOUBLE,
	credit_spread_aaa_gov_10y DOUBLE,
	spread_aaa_bank_gov_3y DOUBLE,
	spread_aaa_bank_gov_5y DOUBLE,
	spread_aaa_bank_gov_7y DOUBLE,
	spread_aaa_bank_gov_10y DOUBLE,
	spread_bank_mtn_3y DOUBLE,
	spread_bank_mtn_5y DOUBLE,
	spread_bank_mtn_7y DOUBLE,
	spread_bank_mtn_10y DOUBLE,
	cpi_yoy DOUBLE,
	ppi_yoy DOUBLE,
	pmi_mfg DOUBLE,
	gdp_yoy DOUBLE,
	m2_yoy DOUBLE,
	social_financing_stock DOUBLE,
	new_rmb_loan DOUBLE,
	rmb_index_month_end DOUBLE,
	rmb_index_ret_1m DOUBLE,
	social_financing_yoy DOUBLE,
	term_spread DOUBLE,
	northbound_net_buy_1m DOUBLE,
	margin_balance_month_end DOUBLE,
	margin_balance_change_1m DOUBLE,
	all_a_pe_ttm_median DOUBLE,
	all_a_pb_median DOUBLE,
	all_a_pe_ttm_percentile DOUBLE,
	all_a_pb_percentile DOUBLE,
	erp DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'regime_ma200_breadth_daily' ( 
	trade_date TIMESTAMP,
	above_ma200_count INT,
	eligible_count INT,
	target_universe_count INT,
	insufficient_history_count INT,
	breadth_ratio DOUBLE,
	coverage_ratio DOUBLE,
	universe_source_count INT,
	excluded_non_a_share_count INT,
	excluded_st_count INT,
	excluded_suspended_count INT,
	universe_version SYMBOL,
	universe_fingerprint STRING,
	hs300_close DOUBLE,
	hs300_source_as_of TIMESTAMP,
	hs300_quality_status SYMBOL,
	hs300_reason_code SYMBOL,
	hs300_reason_message STRING,
	quality_status SYMBOL,
	reason_code SYMBOL,
	reason_message STRING,
	method_version SYMBOL,
	source_lineage STRING,
	input_fingerprint STRING,
	run_id STRING,
	computed_at TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(trade_date,universe_version,method_version);

CREATE TABLE 'regime_margin_leverage_daily' ( 
	trade_date TIMESTAMP,
	margin_balance DOUBLE,
	free_float_market_cap DOUBLE,
	margin_ratio DOUBLE,
	aggregate_margin_balance DOUBLE,
	detail_margin_balance DOUBLE,
	reconciliation_gap_ratio DOUBLE,
	source_mode SYMBOL,
	fallback_used BOOLEAN,
	exchange_coverage STRING,
	duplicate_rows_dropped INT,
	conflicting_exchange_count INT,
	market_cap_security_count INT,
	quality_status SYMBOL,
	reason_code SYMBOL,
	reason_message STRING,
	method_version SYMBOL,
	source_lineage STRING,
	input_fingerprint STRING,
	run_id STRING,
	computed_at TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(trade_date,method_version);

CREATE TABLE 'regime_market_monthly' ( 
	month TIMESTAMP,
	northbound_net_buy_1m DOUBLE,
	margin_balance_month_end DOUBLE,
	margin_balance_change_1m DOUBLE,
	all_a_pe_ttm_median DOUBLE,
	all_a_pb_median DOUBLE,
	all_a_pe_ttm_percentile DOUBLE,
	all_a_pb_percentile DOUBLE,
	erp DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'regime_stock_return_distribution_daily' ( 
	as_of TIMESTAMP,
	window SYMBOL,
	window_start_as_of TIMESTAMP,
	bucket_order INT,
	bucket_id SYMBOL,
	label STRING,
	lower_bound DOUBLE,
	lower_inclusive BOOLEAN,
	upper_bound DOUBLE,
	upper_inclusive BOOLEAN,
	stock_count INT,
	stock_pct DOUBLE,
	valid_return_count INT,
	target_universe_count INT,
	missing_start_count INT,
	missing_end_count INT,
	universe_source_count INT,
	excluded_non_a_share_count INT,
	excluded_st_count INT,
	excluded_suspended_count INT,
	universe_version SYMBOL,
	universe_fingerprint STRING,
	quality_status SYMBOL,
	reason_code SYMBOL,
	reason_message STRING,
	coverage_ratio DOUBLE,
	method_version SYMBOL,
	source_lineage STRING,
	input_fingerprint STRING,
	run_id STRING,
	computed_at TIMESTAMP
) timestamp(as_of) PARTITION BY YEAR
DEDUP UPSERT KEYS(as_of,window,bucket_id,universe_version,method_version);

CREATE TABLE 'repurchase' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	end_date STRING,
	proc SYMBOL,
	exp_date STRING,
	vol DOUBLE,
	amount DOUBLE,
	high_limit DOUBLE,
	low_limit DOUBLE
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date,proc);

CREATE TABLE 'retail_sentiment_daily_cache' ( 
	trade_date TIMESTAMP,
	avg_retail_ratio DOUBLE,
	avg_retail_entropy DOUBLE,
	total_retail_amount_yi DOUBLE,
	total_retail_net_inflow_yi DOUBLE,
	avg_rel_aggro DOUBLE,
	total_q1 LONG,
	total_q3 LONG,
	avg_wash_trade_ratio DOUBLE,
	total_spoof_count LONG,
	total_manipulation_count LONG,
	avg_mfi_score DOUBLE,
	total_main_net_yi DOUBLE,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'rmb_index_daily' ( 
	trade_date TIMESTAMP,
	usd_per_cny DOUBLE,
	eur_per_cny DOUBLE,
	jpy_per_cny DOUBLE,
	gbp_per_cny DOUBLE,
	rmb_index DOUBLE,
	source SYMBOL
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(trade_date);

CREATE TABLE 'sf_month' ( 
	month TIMESTAMP,
	inc_month DOUBLE,
	inc_cumval DOUBLE,
	stk_endval DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'sf_month_backup_20260928_172508_654300' ( 
	month TIMESTAMP,
	inc_month DOUBLE,
	inc_cumval DOUBLE,
	stk_endval DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'sge_daily' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	close DOUBLE,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	price_avg DOUBLE,
	change DOUBLE,
	pct_change DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	oi DOUBLE,
	settle_vol DOUBLE,
	settle_dire STRING
) timestamp(trade_date) PARTITION BY YEAR;

CREATE TABLE 'sge_daily_backup_20260928_174201_962179' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	close DOUBLE,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	price_avg DOUBLE,
	change DOUBLE,
	pct_change DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	oi DOUBLE,
	settle_vol DOUBLE,
	settle_dire STRING
) timestamp(trade_date) PARTITION BY YEAR;

CREATE TABLE 'share_float' ( 
	ts_code SYMBOL,
	ann_date STRING,
	float_date TIMESTAMP,
	float_share DOUBLE,
	float_ratio DOUBLE,
	holder_name STRING,
	share_type SYMBOL
) timestamp(float_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,float_date,holder_name);

CREATE TABLE 'share_float_backup_20260928_184417_615630' ( 
	ts_code SYMBOL,
	ann_date STRING,
	float_date TIMESTAMP,
	float_share DOUBLE,
	float_ratio DOUBLE,
	holder_name STRING,
	share_type SYMBOL
) timestamp(float_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,float_date,holder_name);

CREATE TABLE 'shibor' ( 
	timestamp TIMESTAMP,
	on DOUBLE,
	1w DOUBLE,
	2w DOUBLE,
	1m DOUBLE,
	3m DOUBLE,
	6m DOUBLE,
	9m DOUBLE,
	1y DOUBLE
) timestamp(timestamp) PARTITION BY YEAR
DEDUP UPSERT KEYS(timestamp);

CREATE TABLE 'shibor_backup_20260928_174105_840230' ( 
	timestamp TIMESTAMP,
	on DOUBLE,
	1w DOUBLE,
	2w DOUBLE,
	1m DOUBLE,
	3m DOUBLE,
	6m DOUBLE,
	9m DOUBLE,
	1y DOUBLE
) timestamp(timestamp) PARTITION BY YEAR
DEDUP UPSERT KEYS(timestamp);

CREATE TABLE 'shibor_lpr' ( 
	date TIMESTAMP,
	1y DOUBLE,
	5y DOUBLE
) timestamp(date) PARTITION BY YEAR
DEDUP UPSERT KEYS(date);

CREATE TABLE 'stk_alert' ( 
	ts_code SYMBOL,
	name STRING,
	start_date TIMESTAMP,
	end_date STRING,
	type SYMBOL
) timestamp(start_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,start_date,end_date,type);

CREATE TABLE 'stk_alert_backup_20260928_174108_001386' ( 
	ts_code SYMBOL,
	name STRING,
	start_date TIMESTAMP,
	end_date STRING,
	type SYMBOL
) timestamp(start_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,start_date,end_date,type);

CREATE TABLE 'stk_high_shock' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	name STRING,
	trade_market SYMBOL,
	reason STRING,
	period STRING
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date,reason,period);

CREATE TABLE 'stk_holdernumber' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	end_date STRING,
	holder_num LONG
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date);

CREATE TABLE 'stk_holdernumber_backup_20260928_174153_894550' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	end_date STRING,
	holder_num LONG
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,end_date);

CREATE TABLE 'stk_holdertrade' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	holder_name STRING,
	holder_type SYMBOL,
	in_de SYMBOL,
	change_vol DOUBLE,
	change_ratio DOUBLE,
	after_share DOUBLE,
	after_ratio DOUBLE,
	avg_price DOUBLE,
	total_share DOUBLE,
	begin_date STRING,
	close_date STRING
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,holder_name,in_de);

CREATE TABLE 'stk_holdertrade_backup_20260928_174136_599741' ( 
	ts_code SYMBOL,
	ann_date TIMESTAMP,
	holder_name STRING,
	holder_type SYMBOL,
	in_de SYMBOL,
	change_vol DOUBLE,
	change_ratio DOUBLE,
	after_share DOUBLE,
	after_ratio DOUBLE,
	avg_price DOUBLE,
	total_share DOUBLE,
	begin_date STRING,
	close_date STRING
) timestamp(ann_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,ann_date,holder_name,in_de);

CREATE TABLE 'stk_limit' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	up_limit DOUBLE,
	down_limit DOUBLE
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'stk_shock' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	name STRING,
	trade_market SYMBOL,
	reason STRING,
	period STRING
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date,reason,period);

CREATE TABLE 'stk_st_daily' ( 
	ts_code SYMBOL,
	is_st INT,
	timestamp TIMESTAMP
) timestamp(timestamp) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,timestamp);

CREATE TABLE 'stk_st_daily_backup_20260928_075525_541894' ( 
	ts_code SYMBOL,
	is_st INT,
	timestamp TIMESTAMP
) timestamp(timestamp) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,timestamp);

CREATE TABLE 'stk_suspend' ( 
	ts_code SYMBOL,
	is_suspended LONG,
	timestamp TIMESTAMP
) timestamp(timestamp) PARTITION BY DAY
DEDUP UPSERT KEYS(ts_code,timestamp);

CREATE TABLE 'stk_suspend_backup_20260928_081140_043271' ( 
	ts_code SYMBOL,
	is_suspended LONG,
	timestamp TIMESTAMP
) timestamp(timestamp) PARTITION BY DAY;

CREATE TABLE 'stk_suspend_stage_20260928_081044_205698' ( 
	ts_code SYMBOL,
	is_suspended LONG,
	timestamp TIMESTAMP
) timestamp(timestamp) PARTITION BY DAY
DEDUP UPSERT KEYS(ts_code,timestamp);

CREATE TABLE 'stock_detail_info' ( 
	ts_code SYMBOL,
	update_time TIMESTAMP,
	symbol STRING,
	name STRING,
	market STRING,
	exchange STRING,
	list_status STRING,
	list_date STRING,
	fullname STRING,
	enname STRING,
	cnspell STRING,
	area STRING,
	industry STRING,
	curr_type STRING,
	delist_date STRING,
	is_hs STRING,
	act_name STRING,
	act_ent_type STRING
);

CREATE TABLE 'stock_detail_info_backup_pre_rebuild_20260918_183015_855879' ( 
	ts_code SYMBOL,
	update_time TIMESTAMP,
	symbol STRING,
	name STRING,
	market STRING,
	exchange STRING,
	list_status STRING,
	list_date STRING,
	fullname STRING,
	enname STRING,
	cnspell STRING,
	area STRING,
	industry STRING,
	curr_type STRING,
	delist_date STRING,
	is_hs STRING,
	act_name STRING,
	act_ent_type STRING
);

CREATE TABLE 'stock_detail_info_backup_pre_rebuild_20260924_190339_094923' ( 
	ts_code SYMBOL,
	update_time TIMESTAMP,
	symbol STRING,
	name STRING,
	market STRING,
	exchange STRING,
	list_status STRING,
	list_date STRING,
	fullname STRING,
	enname STRING,
	cnspell STRING,
	area STRING,
	industry STRING,
	curr_type STRING,
	delist_date STRING,
	is_hs STRING,
	act_name STRING,
	act_ent_type STRING
);

CREATE TABLE 'stock_minute_bars' ( 
	ts_code SYMBOL,
	bar_time TIMESTAMP,
	source_row_index INT,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	volume DOUBLE,
	amount DOUBLE
) timestamp(bar_time) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,bar_time);

CREATE TABLE 'strategy_backtest_equity_daily' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	signal_date TIMESTAMP,
	execution_date TIMESTAMP,
	valuation_date TIMESTAMP,
	start_equity DOUBLE,
	end_equity DOUBLE,
	gross_return DOUBLE,
	net_return DOUBLE,
	gross_pnl DOUBLE,
	commission DOUBLE,
	stamp_tax DOUBLE,
	management_fee DOUBLE,
	cost DOUBLE,
	trade_value DOUBLE,
	turnover_weight DOUBLE,
	buy_value DOUBLE,
	sell_value DOUBLE,
	trade_count INT,
	stock_trade_count INT,
	etf_trade_count INT,
	selected_st_count INT,
	rebalance_trigger SYMBOL,
	holdings INT,
	stock_weight DOUBLE,
	etf_weight DOUBLE,
	cash_weight DOUBLE,
	regime SYMBOL
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(run_id,signal_date);

CREATE TABLE 'strategy_backtest_positions' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	signal_date TIMESTAMP,
	execution_date TIMESTAMP,
	valuation_date TIMESTAMP,
	symbol SYMBOL,
	asset_type SYMBOL,
	target_weight DOUBLE,
	price DOUBLE,
	signal_price DOUBLE,
	execution_price DOUBLE,
	valuation_price DOUBLE,
	entry_date TIMESTAMP,
	entry_price DOUBLE,
	regime SYMBOL,
	etf_category SYMBOL,
	broad_family SYMBOL,
	selected_reason STRING,
	rebalance_trigger SYMBOL
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(run_id,signal_date,symbol,asset_type);

CREATE TABLE 'strategy_backtest_runs' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	source_strategy SYMBOL,
	backtest_id SYMBOL,
	artifact_dir STRING,
	params_hash SYMBOL,
	code_version STRING,
	start_date TIMESTAMP,
	end_date TIMESTAMP,
	data_asof TIMESTAMP,
	initial_capital DOUBLE,
	final_equity DOUBLE,
	total_return DOUBLE,
	annualized_return DOUBLE,
	annualized_vol DOUBLE,
	sharpe DOUBLE,
	max_drawdown DOUBLE,
	win_rate DOUBLE,
	avg_daily_turnover DOUBLE,
	total_trade_value DOUBLE,
	total_commission DOUBLE,
	total_stamp_tax DOUBLE,
	total_management_fee DOUBLE,
	total_cost DOUBLE,
	trade_count INT,
	avg_stock_weight DOUBLE,
	avg_etf_weight DOUBLE,
	avg_cash_weight DOUBLE,
	notes STRING,
	created_at TIMESTAMP
) timestamp(created_at) PARTITION BY MONTH
DEDUP UPSERT KEYS(run_id,created_at);

CREATE TABLE 'strategy_backtest_trades' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	signal_date TIMESTAMP,
	execution_date TIMESTAMP,
	valuation_date TIMESTAMP,
	symbol SYMBOL,
	asset_type SYMBOL,
	side SYMBOL,
	weight_delta DOUBLE,
	trade_value DOUBLE,
	commission DOUBLE,
	stamp_tax DOUBLE,
	cost DOUBLE,
	name STRING
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(run_id,signal_date,symbol,asset_type,side);

CREATE TABLE 'strategy_daily_runs' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	source_strategy SYMBOL,
	signal_date TIMESTAMP,
	execution_date TIMESTAMP,
	valuation_date TIMESTAMP,
	status SYMBOL,
	rebalance_trigger SYMBOL,
	start_equity DOUBLE,
	end_equity DOUBLE,
	stock_weight DOUBLE,
	etf_weight DOUBLE,
	cash_weight DOUBLE,
	cost DOUBLE,
	trade_count INT,
	selected_st_count INT,
	artifact_dir STRING,
	summary_path STRING,
	created_at TIMESTAMP
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(run_id,signal_date);

CREATE TABLE 'strategy_instances' ( 
	instance_id SYMBOL,
	strategy_code_id SYMBOL,
	name STRING,
	category STRING,
	tags STRING,
	status SYMBOL,
	params STRING,
	notes STRING,
	version STRING,
	runtime_state STRING,
	last_result STRING,
	created_at TIMESTAMP,
	updated_at TIMESTAMP
) timestamp(created_at) PARTITION BY YEAR;

CREATE TABLE 'strategy_production_equity_daily' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	source_strategy SYMBOL,
	source_run_id SYMBOL,
	backtest_run_id SYMBOL,
	signal_date TIMESTAMP,
	execution_date TIMESTAMP,
	valuation_date TIMESTAMP,
	start_equity DOUBLE,
	end_equity DOUBLE,
	gross_return DOUBLE,
	net_return DOUBLE,
	gross_pnl DOUBLE,
	commission DOUBLE,
	stamp_tax DOUBLE,
	management_fee DOUBLE,
	cost DOUBLE,
	trade_value DOUBLE,
	turnover_weight DOUBLE,
	buy_value DOUBLE,
	sell_value DOUBLE,
	trade_count INT,
	stock_trade_count INT,
	etf_trade_count INT,
	selected_st_count INT,
	rebalance_trigger SYMBOL,
	holdings INT,
	stock_weight DOUBLE,
	etf_weight DOUBLE,
	cash_weight DOUBLE,
	regime SYMBOL,
	created_at TIMESTAMP
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(strategy_id,instance_id,signal_date);

CREATE TABLE 'strategy_production_orders' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	source_strategy SYMBOL,
	source_run_id SYMBOL,
	backtest_run_id SYMBOL,
	signal_date TIMESTAMP,
	execution_date TIMESTAMP,
	valuation_date TIMESTAMP,
	symbol SYMBOL,
	asset_type SYMBOL,
	side SYMBOL,
	weight_delta DOUBLE,
	trade_value DOUBLE,
	commission DOUBLE,
	stamp_tax DOUBLE,
	cost DOUBLE,
	created_at TIMESTAMP,
	name STRING
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(strategy_id,instance_id,signal_date,symbol,asset_type,side);

CREATE TABLE 'strategy_production_pnl_attribution_daily' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	source_strategy SYMBOL,
	source_run_id SYMBOL,
	backtest_run_id SYMBOL,
	signal_date TIMESTAMP,
	execution_date TIMESTAMP,
	valuation_date TIMESTAMP,
	symbol SYMBOL,
	asset_type SYMBOL,
	name STRING,
	target_weight DOUBLE,
	execution_price DOUBLE,
	valuation_price DOUBLE,
	position_return DOUBLE,
	gross_return_contribution DOUBLE,
	gross_pnl_contribution DOUBLE,
	trade_cost DOUBLE,
	management_fee_alloc DOUBLE,
	net_pnl_approx DOUBLE,
	etf_score DOUBLE,
	component_l2_score_smooth DOUBLE,
	component_buy_breadth_smooth DOUBLE,
	portfolio_state SYMBOL,
	state_trigger_reason STRING,
	current_drawdown DOUBLE,
	created_at TIMESTAMP
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(strategy_id,instance_id,signal_date,symbol,asset_type);

CREATE TABLE 'strategy_production_positions' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	source_strategy SYMBOL,
	source_run_id SYMBOL,
	backtest_run_id SYMBOL,
	signal_date TIMESTAMP,
	execution_date TIMESTAMP,
	valuation_date TIMESTAMP,
	symbol SYMBOL,
	asset_type SYMBOL,
	name STRING,
	target_weight DOUBLE,
	target_amount DOUBLE,
	price DOUBLE,
	signal_price DOUBLE,
	execution_price DOUBLE,
	valuation_price DOUBLE,
	entry_date TIMESTAMP,
	entry_price DOUBLE,
	etf_score DOUBLE,
	component_l2_score_smooth DOUBLE,
	component_buy_breadth_smooth DOUBLE,
	top3_weight_concentration DOUBLE,
	top10_limit_touch_weight DOUBLE,
	self_overheat_flag BOOLEAN,
	component_crowding_flag BOOLEAN,
	selection_score DOUBLE,
	selected_reason STRING,
	rebalance_trigger SYMBOL,
	etf_category SYMBOL,
	broad_family SYMBOL,
	created_at TIMESTAMP
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(strategy_id,instance_id,signal_date,symbol,asset_type);

CREATE TABLE 'strategy_production_runs' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	source_strategy SYMBOL,
	source_run_id SYMBOL,
	backtest_run_id SYMBOL,
	daily_run_id SYMBOL,
	artifact_manifest_id SYMBOL,
	factor_platform_run_id SYMBOL,
	signal_date TIMESTAMP,
	execution_date TIMESTAMP,
	valuation_date TIMESTAMP,
	status SYMBOL,
	rebalance_trigger SYMBOL,
	start_equity DOUBLE,
	end_equity DOUBLE,
	gross_return DOUBLE,
	net_return DOUBLE,
	stock_weight DOUBLE,
	etf_weight DOUBLE,
	cash_weight DOUBLE,
	cost DOUBLE,
	trade_count INT,
	target_count INT,
	order_count INT,
	artifact_dir STRING,
	summary_path STRING,
	notes STRING,
	created_at TIMESTAMP,
	persisted_at TIMESTAMP
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(strategy_id,instance_id,signal_date);

CREATE TABLE 'strategy_production_state_daily' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	source_strategy SYMBOL,
	source_run_id SYMBOL,
	backtest_run_id SYMBOL,
	signal_date TIMESTAMP,
	execution_date TIMESTAMP,
	valuation_date TIMESTAMP,
	portfolio_state SYMBOL,
	state_trigger_reason STRING,
	state_action STRING,
	active_alpha_count DOUBLE,
	risk_off_count DOUBLE,
	unstable_count DOUBLE,
	selected_avg_component_l2 DOUBLE,
	selected_avg_buy_breadth DOUBLE,
	selected_overheat_ratio DOUBLE,
	selected_crowding_ratio DOUBLE,
	selected_hot_crowded_ratio DOUBLE,
	avg_pair_corr DOUBLE,
	avg_component_overlap DOUBLE,
	top10_high_vol_weight DOUBLE,
	top10_limit_touch_weight DOUBLE,
	component_crowding_score DOUBLE,
	overheat_score DOUBLE,
	current_drawdown DOUBLE,
	net_return DOUBLE,
	gross_return DOUBLE,
	end_equity DOUBLE,
	factor_state_details STRING,
	created_at TIMESTAMP
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(strategy_id,instance_id,signal_date);

CREATE TABLE 'strategy_recommended_orders' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	signal_date TIMESTAMP,
	execution_date TIMESTAMP,
	valuation_date TIMESTAMP,
	symbol SYMBOL,
	asset_type SYMBOL,
	side SYMBOL,
	weight_delta DOUBLE,
	trade_value DOUBLE,
	commission DOUBLE,
	stamp_tax DOUBLE,
	cost DOUBLE,
	name STRING
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(run_id,signal_date,symbol,asset_type,side);

CREATE TABLE 'strategy_run_artifact_manifest' ( 
	artifact_manifest_id SYMBOL,
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	source_strategy SYMBOL,
	artifact_path STRING,
	relative_path STRING,
	artifact_kind SYMBOL,
	artifact_role SYMBOL,
	file_ext SYMBOL,
	bytes LONG,
	rows LONG,
	min_date TIMESTAMP,
	max_date TIMESTAMP,
	mtime TIMESTAMP,
	content_hash STRING,
	is_cache BOOLEAN,
	is_structured BOOLEAN,
	structured_table SYMBOL,
	structured_rows LONG,
	created_at TIMESTAMP
) timestamp(created_at) PARTITION BY MONTH
DEDUP UPSERT KEYS(artifact_manifest_id,artifact_path,created_at);

CREATE TABLE 'strategy_target_positions' ( 
	run_id SYMBOL,
	strategy_id SYMBOL,
	instance_id SYMBOL,
	signal_date TIMESTAMP,
	execution_date TIMESTAMP,
	valuation_date TIMESTAMP,
	symbol SYMBOL,
	asset_type SYMBOL,
	target_weight DOUBLE,
	target_amount DOUBLE,
	signal_price DOUBLE,
	execution_price DOUBLE,
	valuation_price DOUBLE,
	selected_reason STRING,
	rebalance_trigger SYMBOL,
	name STRING
) timestamp(signal_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(run_id,signal_date,symbol,asset_type);

CREATE TABLE 'strategy_templates' ( 
	strategy_code_id SYMBOL,
	name STRING,
	category STRING,
	tags STRING,
	description STRING,
	version STRING,
	author STRING,
	metadata STRING,
	input_schema STRING,
	default_params STRING,
	active BOOLEAN,
	created_at TIMESTAMP,
	updated_at TIMESTAMP
) timestamp(created_at) PARTITION BY YEAR;

CREATE TABLE 'ths_index' ( 
	ts_code SYMBOL,
	name STRING,
	count INT,
	exchange STRING,
	list_date STRING,
	type STRING,
	update_time TIMESTAMP
) timestamp(update_time) PARTITION BY MONTH
DEDUP UPSERT KEYS(ts_code,update_time);

CREATE TABLE 'ths_member' ( 
	ts_code SYMBOL,
	con_code SYMBOL,
	con_name STRING,
	weight DOUBLE,
	in_date STRING,
	out_date STRING,
	is_new STRING,
	update_time TIMESTAMP
) timestamp(update_time) PARTITION BY MONTH
DEDUP UPSERT KEYS(ts_code,con_code,update_time);

CREATE TABLE 'tracked_symbols' ( 
	ts_code SYMBOL,
	position_active BOOLEAN,
	position_volume INT,
	available_volume INT,
	focus_1m BOOLEAN,
	focus_tick BOOLEAN,
	t_mode BOOLEAN,
	priority INT,
	source SYMBOL,
	reason STRING,
	expires_at TIMESTAMP,
	updated_at TIMESTAMP,
	metadata STRING
) timestamp(updated_at) PARTITION BY DAY;

CREATE TABLE 'trade_records' ( 
	trade_id STRING,
	ts_code SYMBOL,
	symbol STRING,
	stock_name STRING,
	trade_date STRING,
	trade_time STRING,
	trade_type STRING,
	trade_price DOUBLE,
	trade_volume INT,
	trade_amount DOUBLE,
	commission DOUBLE,
	stamp_duty DOUBLE,
	transfer_fee DOUBLE,
	total_fee DOUBLE,
	strategy_name STRING,
	portfolio_name STRING,
	note STRING,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	order_id STRING,
	broker_order_id STRING,
	signal_id STRING
) timestamp(create_time) PARTITION BY YEAR;

CREATE TABLE 'trade_signals' ( 
	signal_id SYMBOL,
	ts_code SYMBOL,
	signal_type STRING,
	signal_source STRING,
	signal_time TIMESTAMP,
	signal_price DOUBLE,
	target_price DOUBLE,
	stop_loss DOUBLE,
	quantity INT,
	confidence DOUBLE,
	status STRING,
	reason STRING,
	order_id STRING,
	updated_at TIMESTAMP,
	updated_by STRING,
	reviewed_by STRING,
	status_note STRING
) timestamp(signal_time) PARTITION BY YEAR;

CREATE TABLE 'us_market_daily' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	close DOUBLE,
	source_id SYMBOL,
	source_url STRING,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'us_market_daily_backup_20260928_174118_909851' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	close DOUBLE,
	source_id SYMBOL,
	source_url STRING,
	update_time TIMESTAMP
) timestamp(trade_date) PARTITION BY YEAR
DEDUP UPSERT KEYS(ts_code,trade_date);

CREATE TABLE 'us_tbr' ( 
	date TIMESTAMP,
	w4_bd DOUBLE,
	w4_ce DOUBLE,
	w8_bd DOUBLE,
	w8_ce DOUBLE,
	w13_bd DOUBLE,
	w13_ce DOUBLE,
	w17_bd DOUBLE,
	w17_ce DOUBLE,
	w26_bd DOUBLE,
	w26_ce DOUBLE,
	w52_bd DOUBLE,
	w52_ce DOUBLE
) timestamp(date) PARTITION BY YEAR;

CREATE TABLE 'us_tbr_backup_20260928_174232_820970' ( 
	date TIMESTAMP,
	w4_bd DOUBLE,
	w4_ce DOUBLE,
	w8_bd DOUBLE,
	w8_ce DOUBLE,
	w13_bd DOUBLE,
	w13_ce DOUBLE,
	w17_bd DOUBLE,
	w17_ce DOUBLE,
	w26_bd DOUBLE,
	w26_ce DOUBLE,
	w52_bd DOUBLE,
	w52_ce DOUBLE
) timestamp(date) PARTITION BY YEAR;

CREATE TABLE 'us_tltr' ( 
	date TIMESTAMP,
	ltc DOUBLE,
	cmt DOUBLE,
	e_factor DOUBLE
) timestamp(date) PARTITION BY YEAR;

CREATE TABLE 'us_tltr_backup_20260928_174129_052708' ( 
	date TIMESTAMP,
	ltc DOUBLE,
	cmt DOUBLE,
	e_factor DOUBLE
) timestamp(date) PARTITION BY YEAR;

CREATE TABLE 'us_trltr' ( 
	date TIMESTAMP,
	ltr_avg DOUBLE
) timestamp(date) PARTITION BY YEAR;

CREATE TABLE 'us_trltr_backup_20260928_174121_447233' ( 
	date TIMESTAMP,
	ltr_avg DOUBLE
) timestamp(date) PARTITION BY YEAR;

CREATE TABLE 'us_trycr' ( 
	date TIMESTAMP,
	y5 DOUBLE,
	y7 DOUBLE,
	y10 DOUBLE,
	y20 DOUBLE,
	y30 DOUBLE
) timestamp(date) PARTITION BY YEAR;

CREATE TABLE 'us_trycr_backup_20260928_174131_969180' ( 
	date TIMESTAMP,
	y5 DOUBLE,
	y7 DOUBLE,
	y10 DOUBLE,
	y20 DOUBLE,
	y30 DOUBLE
) timestamp(date) PARTITION BY YEAR;

CREATE TABLE 'us_tycr' ( 
	date TIMESTAMP,
	m1 DOUBLE,
	m2 DOUBLE,
	m3 DOUBLE,
	m4 DOUBLE,
	m6 DOUBLE,
	y1 DOUBLE,
	y2 DOUBLE,
	y3 DOUBLE,
	y5 DOUBLE,
	y7 DOUBLE,
	y10 DOUBLE,
	y20 DOUBLE,
	y30 DOUBLE
) timestamp(date) PARTITION BY YEAR;

CREATE TABLE 'us_tycr_backup_20260928_174240_913897' ( 
	date TIMESTAMP,
	m1 DOUBLE,
	m2 DOUBLE,
	m3 DOUBLE,
	m4 DOUBLE,
	m6 DOUBLE,
	y1 DOUBLE,
	y2 DOUBLE,
	y3 DOUBLE,
	y5 DOUBLE,
	y7 DOUBLE,
	y10 DOUBLE,
	y20 DOUBLE,
	y30 DOUBLE
) timestamp(date) PARTITION BY YEAR;

CREATE VIEW 'v_backtest_daily' AS ( 
SELECT b.trade_date, b.ts_code, b.open, b.high, b.low, b.close,
       b.vol, b.amount, b.adj_factor, l.up_limit, l.down_limit,
       coalesce(s.is_suspended, 0) is_suspended, coalesce(st.is_st, 0) is_st
FROM stk_factor b
LEFT JOIN stk_limit l ON (b.trade_date = l.trade_date AND b.ts_code = l.ts_code)
LEFT JOIN (SELECT timestamp, ts_code, max(is_suspended) is_suspended
           FROM stk_suspend GROUP BY timestamp, ts_code) s ON (b.trade_date = s.timestamp AND b.ts_code = s.ts_code)
LEFT JOIN stk_st_daily st ON (b.trade_date = st.timestamp AND b.ts_code = st.ts_code)
UNION ALL
SELECT s.trade_date, s.ts_code, f.close AS open, f.close AS high,
       f.close AS low, f.close AS close, 0.0 AS vol, 0.0 AS amount,
       f.adj_factor, l.up_limit, l.down_limit, s.is_suspended,
       coalesce(st.is_st, 0) is_st
FROM ((SELECT timestamp AS trade_date, ts_code, max(is_suspended) is_suspended
      FROM stk_suspend GROUP BY timestamp, ts_code ORDER BY trade_date) TIMESTAMP(trade_date)) s
ASOF JOIN stk_factor f ON (ts_code)
LEFT JOIN stk_limit l ON (s.trade_date = l.trade_date AND s.ts_code = l.ts_code)
LEFT JOIN stk_st_daily st ON (s.trade_date = st.timestamp AND s.ts_code = st.ts_code)
JOIN (SELECT DISTINCT trade_date FROM stk_factor) fd ON s.trade_date = fd.trade_date
WHERE f.trade_date < s.trade_date
);

CREATE VIEW 'v_etf_market_overview_daily' AS ( 
SELECT
    s.timestamp AS trade_date,
    count_distinct(s.ts_code) AS etf_count,
    sum(s.fd_share) AS total_share,
    sum(s.fd_share * d.close) / 10000.0 AS total_size_yi
FROM etf_share s
JOIN etf_daily d ON s.ts_code = d.ts_code AND s.timestamp = d.timestamp

SAMPLE BY 1d ALIGN TO CALENDAR
);

CREATE VIEW 'v_macro_core_monthly' AS ( 
SELECT * FROM macro_core_monthly
);

CREATE VIEW 'v_macro_liquidity_credit_monthly' AS ( 
SELECT * FROM macro_liquidity_credit_monthly
);

CREATE VIEW 'v_market_breadth_daily' AS ( 
SELECT * FROM mv_market_breadth_daily_v1
);

CREATE VIEW 'v_market_breadth_monthly' AS ( 
SELECT * FROM market_breadth_monthly
);

CREATE VIEW 'v_regime_features_monitor_daily' AS ( 
SELECT * FROM regime_features_monitor_daily
);

CREATE VIEW 'v_regime_features_monthly' AS ( 
SELECT * FROM regime_features_monthly
);

CREATE VIEW 'v_regime_market_monthly' AS ( 
SELECT * FROM regime_market_monthly
);

CREATE VIEW 'v_retail_sentiment_daily' AS ( 
SELECT * FROM mv_retail_sentiment_daily_v1
);

CREATE TABLE 'wal_chunk_drill_20260928_173652' ( 
	month TIMESTAMP,
	nt_val DOUBLE,
	nt_yoy DOUBLE,
	nt_mom DOUBLE,
	nt_accu DOUBLE,
	town_val DOUBLE,
	town_yoy DOUBLE,
	town_mom DOUBLE,
	town_accu DOUBLE,
	cnt_val DOUBLE,
	cnt_yoy DOUBLE,
	cnt_mom DOUBLE,
	cnt_accu DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'wal_chunk_drill_20260928_173652_backup_20260928_173652_618228' ( 
	month TIMESTAMP,
	nt_val DOUBLE,
	nt_yoy DOUBLE,
	nt_mom DOUBLE,
	nt_accu DOUBLE,
	town_val DOUBLE,
	town_yoy DOUBLE,
	town_mom DOUBLE,
	town_accu DOUBLE,
	cnt_val DOUBLE,
	cnt_yoy DOUBLE,
	cnt_mom DOUBLE,
	cnt_accu DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'wal_drill_lost_ack_20260928_172008' ( 
	month TIMESTAMP,
	nt_val DOUBLE,
	nt_yoy DOUBLE,
	nt_mom DOUBLE,
	nt_accu DOUBLE,
	town_val DOUBLE,
	town_yoy DOUBLE,
	town_mom DOUBLE,
	town_accu DOUBLE,
	cnt_val DOUBLE,
	cnt_yoy DOUBLE,
	cnt_mom DOUBLE,
	cnt_accu DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'wal_drill_lost_ack_20260928_172008_stage_20260928_172008_937981_failed' ( 
	month TIMESTAMP,
	nt_val DOUBLE,
	nt_yoy DOUBLE,
	nt_mom DOUBLE,
	nt_accu DOUBLE,
	town_val DOUBLE,
	town_yoy DOUBLE,
	town_mom DOUBLE,
	town_accu DOUBLE,
	cnt_val DOUBLE,
	cnt_yoy DOUBLE,
	cnt_mom DOUBLE,
	cnt_accu DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'wal_drill_normal_20260928_172005' ( 
	month TIMESTAMP,
	nt_val DOUBLE,
	nt_yoy DOUBLE,
	nt_mom DOUBLE,
	nt_accu DOUBLE,
	town_val DOUBLE,
	town_yoy DOUBLE,
	town_mom DOUBLE,
	town_accu DOUBLE,
	cnt_val DOUBLE,
	cnt_yoy DOUBLE,
	cnt_mom DOUBLE,
	cnt_accu DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'wal_drill_normal_20260928_172005_backup_20260928_172006_583513' ( 
	month TIMESTAMP,
	nt_val DOUBLE,
	nt_yoy DOUBLE,
	nt_mom DOUBLE,
	nt_accu DOUBLE,
	town_val DOUBLE,
	town_yoy DOUBLE,
	town_mom DOUBLE,
	town_accu DOUBLE,
	cnt_val DOUBLE,
	cnt_yoy DOUBLE,
	cnt_mom DOUBLE,
	cnt_accu DOUBLE
) timestamp(month) PARTITION BY YEAR
DEDUP UPSERT KEYS(month);

CREATE TABLE 'wal_duplicate_drill_20260928_173652' ( 
	ts TIMESTAMP,
	value INT,
	historical STRING
) timestamp(ts) PARTITION BY DAY
DEDUP UPSERT KEYS(ts);

CREATE TABLE 'wal_duplicate_drill_20260928_173652_backup_20260928_173659_027533' ( 
	ts TIMESTAMP,
	value INT,
	historical STRING
) timestamp(ts) PARTITION BY DAY
DEDUP UPSERT KEYS(ts);

CREATE TABLE 'wal_inventory_drill_20260928_204930' ( 
	ts TIMESTAMP,
	k SYMBOL,
	v DOUBLE,
	n LONG,
	s STRING
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_bigint_one_unit_20260928_221246' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_bigint_one_unit_20260928_221743' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_bigint_one_unit_20260928_222723' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_bigint_one_unit_20260928_224228' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_double_one_ulp_20260928_221743' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_double_one_ulp_20260928_222723' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_double_one_ulp_20260928_224228' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_equal_20260928_221246' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_equal_20260928_221743' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_equal_20260928_222723' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_equal_20260928_224228' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_equal_reversed_dictionary_20260928_224228' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_extra_duplicate_20260928_221246' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_extra_duplicate_20260928_221743' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_extra_duplicate_20260928_222723' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_extra_duplicate_20260928_224228' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_left_20260928_221246' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_left_20260928_221743' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_left_20260928_222723' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_left_20260928_224228' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_lifecycle_20260928_222010' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_lifecycle_20260928_222010_backup_20260928_222010_938442' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY
DEDUP UPSERT KEYS(ts,k);

CREATE TABLE 'wal_multiset_lifecycle_20260928_224318' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_lifecycle_20260928_224318_backup_20260928_224319_367501' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY
DEDUP UPSERT KEYS(ts,k);

CREATE TABLE 'wal_multiset_missing_row_20260928_221246' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_missing_row_20260928_221743' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_missing_row_20260928_222723' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_missing_row_20260928_224228' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_null_to_finite_20260928_221743' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_null_to_finite_20260928_222723' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_null_to_finite_20260928_224228' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_null_two_vs_one_20260928_221246' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_null_two_vs_one_20260928_221743' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_null_two_vs_one_20260928_222723' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_null_two_vs_one_20260928_224228' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_same_count_missing_extra_20260928_221246' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_same_count_missing_extra_20260928_221743' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_same_count_missing_extra_20260928_222723' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_same_count_missing_extra_20260928_224228' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_single_string_value_20260928_221246' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_single_string_value_20260928_221743' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_single_string_value_20260928_222723' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_single_string_value_20260928_224228' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_symbol_value_20260928_224228' ( 
	ts TIMESTAMP,
	k SYMBOL,
	n LONG,
	v DOUBLE,
	s STRING,
	b BOOLEAN,
	f FLOAT
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_multiset_wide_20260928_222205' ( 
	trade_date STRING,
	symbol SYMBOL,
	market STRING,
	board STRING,
	minute TIMESTAMP,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	volume DOUBLE,
	amount DOUBLE,
	tick_count LONG,
	active_buy_amount DOUBLE,
	active_sell_amount DOUBLE,
	vwap DOUBLE,
	has_trade_1m LONG,
	bid1 DOUBLE,
	ask1 DOUBLE,
	mid DOUBLE,
	spread DOUBLE,
	microprice DOUBLE,
	bid_depth_1 DOUBLE,
	ask_depth_1 DOUBLE,
	depth_1 DOUBLE,
	obi_1 DOUBLE,
	bid_depth_5 DOUBLE,
	ask_depth_5 DOUBLE,
	depth_5 DOUBLE,
	obi_5 DOUBLE,
	bid_depth_10 DOUBLE,
	ask_depth_10 DOUBLE,
	depth_10 DOUBLE,
	obi_10 DOUBLE,
	quote_count DOUBLE,
	ofi_1m DOUBLE,
	active_buy_ratio DOUBLE,
	active_sell_ratio DOUBLE,
	vwap_gap_to_mid DOUBLE,
	vwap_gap_to_open DOUBLE,
	vwap_slope_3m DOUBLE,
	vwap_slope_5m DOUBLE,
	ret_1m DOUBLE,
	vol_ratio_1m DOUBLE,
	range_1m DOUBLE,
	ret_3m DOUBLE,
	vol_ratio_3m DOUBLE,
	range_3m DOUBLE,
	ret_5m DOUBLE,
	vol_ratio_5m DOUBLE,
	range_5m DOUBLE,
	ret_10m DOUBLE,
	vol_ratio_10m DOUBLE,
	range_10m DOUBLE,
	ret_15m DOUBLE,
	vol_ratio_15m DOUBLE,
	range_15m DOUBLE,
	ret_30m DOUBLE,
	vol_ratio_30m DOUBLE,
	range_30m DOUBLE,
	cancel_ratio DOUBLE,
	event_type STRING,
	future_vwap_return_1m DOUBLE,
	future_mid_return_1m DOUBLE,
	future_vwap_return_3m DOUBLE,
	future_mid_return_3m DOUBLE,
	future_vwap_return_5m DOUBLE,
	future_mid_return_5m DOUBLE,
	future_vwap_return_10m DOUBLE,
	future_mid_return_10m DOUBLE,
	future_vwap_return_15m DOUBLE,
	future_mid_return_15m DOUBLE,
	future_vwap_return_30m DOUBLE,
	future_mid_return_30m DOUBLE
) timestamp(minute) PARTITION BY DAY
DEDUP UPSERT KEYS(symbol,minute,event_type);

CREATE TABLE 'wal_multiset_wide_20260928_222205_backup_20260928_222229_124832' ( 
	trade_date STRING,
	symbol SYMBOL,
	market STRING,
	board STRING,
	minute TIMESTAMP,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	volume DOUBLE,
	amount DOUBLE,
	tick_count LONG,
	active_buy_amount DOUBLE,
	active_sell_amount DOUBLE,
	vwap DOUBLE,
	has_trade_1m LONG,
	bid1 DOUBLE,
	ask1 DOUBLE,
	mid DOUBLE,
	spread DOUBLE,
	microprice DOUBLE,
	bid_depth_1 DOUBLE,
	ask_depth_1 DOUBLE,
	depth_1 DOUBLE,
	obi_1 DOUBLE,
	bid_depth_5 DOUBLE,
	ask_depth_5 DOUBLE,
	depth_5 DOUBLE,
	obi_5 DOUBLE,
	bid_depth_10 DOUBLE,
	ask_depth_10 DOUBLE,
	depth_10 DOUBLE,
	obi_10 DOUBLE,
	quote_count DOUBLE,
	ofi_1m DOUBLE,
	active_buy_ratio DOUBLE,
	active_sell_ratio DOUBLE,
	vwap_gap_to_mid DOUBLE,
	vwap_gap_to_open DOUBLE,
	vwap_slope_3m DOUBLE,
	vwap_slope_5m DOUBLE,
	ret_1m DOUBLE,
	vol_ratio_1m DOUBLE,
	range_1m DOUBLE,
	ret_3m DOUBLE,
	vol_ratio_3m DOUBLE,
	range_3m DOUBLE,
	ret_5m DOUBLE,
	vol_ratio_5m DOUBLE,
	range_5m DOUBLE,
	ret_10m DOUBLE,
	vol_ratio_10m DOUBLE,
	range_10m DOUBLE,
	ret_15m DOUBLE,
	vol_ratio_15m DOUBLE,
	range_15m DOUBLE,
	ret_30m DOUBLE,
	vol_ratio_30m DOUBLE,
	range_30m DOUBLE,
	cancel_ratio DOUBLE,
	event_type STRING,
	future_vwap_return_1m DOUBLE,
	future_mid_return_1m DOUBLE,
	future_vwap_return_3m DOUBLE,
	future_mid_return_3m DOUBLE,
	future_vwap_return_5m DOUBLE,
	future_mid_return_5m DOUBLE,
	future_vwap_return_10m DOUBLE,
	future_mid_return_10m DOUBLE,
	future_vwap_return_15m DOUBLE,
	future_mid_return_15m DOUBLE,
	future_vwap_return_30m DOUBLE,
	future_mid_return_30m DOUBLE
) timestamp(minute) PARTITION BY DAY
DEDUP UPSERT KEYS(symbol,minute,event_type);

CREATE TABLE 'wal_multiset_wide_20260928_224358' ( 
	trade_date STRING,
	symbol SYMBOL,
	market STRING,
	board STRING,
	minute TIMESTAMP,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	volume DOUBLE,
	amount DOUBLE,
	tick_count LONG,
	active_buy_amount DOUBLE,
	active_sell_amount DOUBLE,
	vwap DOUBLE,
	has_trade_1m LONG,
	bid1 DOUBLE,
	ask1 DOUBLE,
	mid DOUBLE,
	spread DOUBLE,
	microprice DOUBLE,
	bid_depth_1 DOUBLE,
	ask_depth_1 DOUBLE,
	depth_1 DOUBLE,
	obi_1 DOUBLE,
	bid_depth_5 DOUBLE,
	ask_depth_5 DOUBLE,
	depth_5 DOUBLE,
	obi_5 DOUBLE,
	bid_depth_10 DOUBLE,
	ask_depth_10 DOUBLE,
	depth_10 DOUBLE,
	obi_10 DOUBLE,
	quote_count DOUBLE,
	ofi_1m DOUBLE,
	active_buy_ratio DOUBLE,
	active_sell_ratio DOUBLE,
	vwap_gap_to_mid DOUBLE,
	vwap_gap_to_open DOUBLE,
	vwap_slope_3m DOUBLE,
	vwap_slope_5m DOUBLE,
	ret_1m DOUBLE,
	vol_ratio_1m DOUBLE,
	range_1m DOUBLE,
	ret_3m DOUBLE,
	vol_ratio_3m DOUBLE,
	range_3m DOUBLE,
	ret_5m DOUBLE,
	vol_ratio_5m DOUBLE,
	range_5m DOUBLE,
	ret_10m DOUBLE,
	vol_ratio_10m DOUBLE,
	range_10m DOUBLE,
	ret_15m DOUBLE,
	vol_ratio_15m DOUBLE,
	range_15m DOUBLE,
	ret_30m DOUBLE,
	vol_ratio_30m DOUBLE,
	range_30m DOUBLE,
	cancel_ratio DOUBLE,
	event_type STRING,
	future_vwap_return_1m DOUBLE,
	future_mid_return_1m DOUBLE,
	future_vwap_return_3m DOUBLE,
	future_mid_return_3m DOUBLE,
	future_vwap_return_5m DOUBLE,
	future_mid_return_5m DOUBLE,
	future_vwap_return_10m DOUBLE,
	future_mid_return_10m DOUBLE,
	future_vwap_return_15m DOUBLE,
	future_mid_return_15m DOUBLE,
	future_vwap_return_30m DOUBLE,
	future_mid_return_30m DOUBLE
) timestamp(minute) PARTITION BY DAY
DEDUP UPSERT KEYS(symbol,minute,event_type);

CREATE TABLE 'wal_multiset_wide_20260928_224358_backup_20260928_224422_353693' ( 
	trade_date STRING,
	symbol SYMBOL,
	market STRING,
	board STRING,
	minute TIMESTAMP,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	volume DOUBLE,
	amount DOUBLE,
	tick_count LONG,
	active_buy_amount DOUBLE,
	active_sell_amount DOUBLE,
	vwap DOUBLE,
	has_trade_1m LONG,
	bid1 DOUBLE,
	ask1 DOUBLE,
	mid DOUBLE,
	spread DOUBLE,
	microprice DOUBLE,
	bid_depth_1 DOUBLE,
	ask_depth_1 DOUBLE,
	depth_1 DOUBLE,
	obi_1 DOUBLE,
	bid_depth_5 DOUBLE,
	ask_depth_5 DOUBLE,
	depth_5 DOUBLE,
	obi_5 DOUBLE,
	bid_depth_10 DOUBLE,
	ask_depth_10 DOUBLE,
	depth_10 DOUBLE,
	obi_10 DOUBLE,
	quote_count DOUBLE,
	ofi_1m DOUBLE,
	active_buy_ratio DOUBLE,
	active_sell_ratio DOUBLE,
	vwap_gap_to_mid DOUBLE,
	vwap_gap_to_open DOUBLE,
	vwap_slope_3m DOUBLE,
	vwap_slope_5m DOUBLE,
	ret_1m DOUBLE,
	vol_ratio_1m DOUBLE,
	range_1m DOUBLE,
	ret_3m DOUBLE,
	vol_ratio_3m DOUBLE,
	range_3m DOUBLE,
	ret_5m DOUBLE,
	vol_ratio_5m DOUBLE,
	range_5m DOUBLE,
	ret_10m DOUBLE,
	vol_ratio_10m DOUBLE,
	range_10m DOUBLE,
	ret_15m DOUBLE,
	vol_ratio_15m DOUBLE,
	range_15m DOUBLE,
	ret_30m DOUBLE,
	vol_ratio_30m DOUBLE,
	range_30m DOUBLE,
	cancel_ratio DOUBLE,
	event_type STRING,
	future_vwap_return_1m DOUBLE,
	future_mid_return_1m DOUBLE,
	future_vwap_return_3m DOUBLE,
	future_mid_return_3m DOUBLE,
	future_vwap_return_5m DOUBLE,
	future_mid_return_5m DOUBLE,
	future_vwap_return_10m DOUBLE,
	future_mid_return_10m DOUBLE,
	future_vwap_return_15m DOUBLE,
	future_mid_return_15m DOUBLE,
	future_vwap_return_30m DOUBLE,
	future_mid_return_30m DOUBLE
) timestamp(minute) PARTITION BY DAY
DEDUP UPSERT KEYS(symbol,minute,event_type);

CREATE TABLE 'wal_pair_cov_20260928_201235_590298' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202007_065999' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202013_956234' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202013_956234_stage_20260928_202014_860556_failed' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202026_427391' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202026_427391_stage_20260928_202027_648726_failed' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202154_272654' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202201_387027' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202201_387027_stage_20260928_202202_605358_failed' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202214_016015' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202214_016015_backup_20260928_202215_065988' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202913_087217' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202921_315542' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202921_315542_stage_20260928_202922_260847_failed' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202936_220850' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_cov_20260928_202936_220850_backup_20260928_202937_194433' ( 
	trade_date TIMESTAMP,
	source_version SYMBOL,
	row_count LONG,
	content_digest STRING
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,source_version);

CREATE TABLE 'wal_pair_data_20260928_201235_590298' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_201235_590298_stage_20260928_201236_720868_failed' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202007_065999' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202007_065999_stage_20260928_202007_939943_failed' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202013_956234' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202013_956234_stage_20260928_202014_833454_failed' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202026_427391' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202026_427391_stage_20260928_202027_609436_failed' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202154_272654' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202154_272654_stage_20260928_202155_460072_failed' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202201_387027' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202201_387027_stage_20260928_202202_561332_failed' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202214_016015' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202214_016015_backup_20260928_202215_027340' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202913_087217' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202913_087217_stage_20260928_202913_975031_failed' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202921_315542' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202921_315542_stage_20260928_202922_226361_failed' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202936_220850' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_pair_data_20260928_202936_220850_backup_20260928_202937_152703' ( 
	trade_date TIMESTAMP,
	ts_code SYMBOL,
	open DOUBLE,
	high DOUBLE,
	low DOUBLE,
	close DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	adj_factor DOUBLE,
	up_limit DOUBLE,
	down_limit DOUBLE,
	is_suspended INT,
	is_st INT,
	source_version SYMBOL
) timestamp(trade_date) PARTITION BY MONTH
DEDUP UPSERT KEYS(trade_date,ts_code,source_version);

CREATE TABLE 'wal_prefreeze_sequence_20260928_230502' ( 
	ts TIMESTAMP,
	n LONG
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_prepare_only_20260928_215155' ( 
	ts TIMESTAMP,
	value LONG,
	text_value STRING
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_prepare_only_20260928_215155_backup_20260928_215156_159366' ( 
	ts TIMESTAMP,
	value LONG,
	text_value STRING
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_prestage_resume_20260928_193521' ( 
	ts TIMESTAMP,
	value LONG,
	text_value STRING
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_prestage_resume_20260928_193521_backup_20260928_193521_987961' ( 
	ts TIMESTAMP,
	value LONG,
	text_value STRING
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_projection_drill_20260928_183345' ( 
	ts TIMESTAMP,
	value LONG,
	text_value STRING
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_projection_drill_20260928_183345_backup_20260928_183346_185827' ( 
	ts TIMESTAMP,
	value LONG,
	text_value STRING
) timestamp(ts) PARTITION BY DAY;

CREATE TABLE 'wal_text_drill_20260928_180436' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	price DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	buyer STRING,
	seller STRING
) timestamp(trade_date) PARTITION BY YEAR;

CREATE TABLE 'wal_text_drill_20260928_180436_backup_20260928_180437_009053' ( 
	ts_code SYMBOL,
	trade_date TIMESTAMP,
	price DOUBLE,
	vol DOUBLE,
	amount DOUBLE,
	buyer STRING,
	seller STRING
) timestamp(trade_date) PARTITION BY YEAR;
