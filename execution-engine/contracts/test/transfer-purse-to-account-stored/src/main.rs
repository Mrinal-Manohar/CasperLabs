#![no_std]
#![no_main]

extern crate alloc;

use alloc::{string::ToString, vec};

use alloc::boxed::Box;
use contract::contract_api::storage;

use types::{
    contracts::{EntryPoint, EntryPointAccess, EntryPointType, EntryPoints, Parameter},
    CLType,
};

const ENTRY_FUNCTION_NAME: &str = "transfer";
const HASH_KEY_NAME: &str = "transfer_purse_to_account";
const ACCESS_KEY_NAME: &str = "transfer_purse_to_account_access";
const ARG_0_NAME: &str = "target_account_addr";
const ARG_1_NAME: &str = "amount";

#[no_mangle]
pub extern "C" fn transfer() {
    transfer_purse_to_account::delegate();
}

#[no_mangle]
pub extern "C" fn call() {
    let entry_points = {
        let mut entry_points = EntryPoints::new();

        let entry_point = EntryPoint::new(
            ENTRY_FUNCTION_NAME.to_string(),
            vec![
                Parameter::new(ARG_0_NAME, CLType::FixedList(Box::new(CLType::U8), 32)),
                Parameter::new(ARG_1_NAME, CLType::U512),
            ],
            CLType::Unit,
            EntryPointAccess::Public,
            EntryPointType::Session,
        );
        entry_points.add_entry_point(entry_point);
        entry_points
    };

    storage::new_contract(
        entry_points,
        None,
        Some(HASH_KEY_NAME.to_string()),
        Some(ACCESS_KEY_NAME.to_string()),
    );
}
