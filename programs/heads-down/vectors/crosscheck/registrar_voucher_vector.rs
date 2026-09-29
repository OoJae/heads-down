//! Cross-check example, compiled inside a temporary copy of `registrar/` by
//! `registrar_voucher.sh` (the registrar directory itself is never edited):
//! the registrar's own `voucher.rs` signs the voucher described in
//! `vectors/registrar.json` and every byte is compared.
use hd_registrar::voucher::{RegistrarKey, Voucher, HEADS_DOWN_PROGRAM_ID, IX_HEADER};

fn main() {
    let dir = std::env::var("VECTORS").expect("VECTORS = programs/heads-down/vectors");
    let g: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(format!("{dir}/registrar.json")).unwrap()).unwrap();
    let v = &g["voucher"];
    let key = RegistrarKey::from_seed(&[5u8; 32]); // registrar.json: registrar_key.seed
    let b58 = |s: &str| -> [u8; 32] { bs58::decode(s).into_vec().unwrap().try_into().unwrap() };
    let signed = key.sign(Voucher {
        program_id: b58(HEADS_DOWN_PROGRAM_ID),
        authority: b58(v["authority"].as_str().unwrap()),
        p256_pubkey: hex::decode(v["p256_pubkey_hex"].as_str().unwrap()).unwrap().try_into().unwrap(),
        level: v["level"].as_u64().unwrap() as u8,
        expiry_slot: v["expiry_slot"].as_str().unwrap().parse().unwrap(),
    });
    let check = |what: &str, ours: String, golden: &serde_json::Value| {
        let ok = ours == golden.as_str().unwrap();
        println!("[{}] registrar {what}", if ok { "MATCH" } else { "MISMATCH" });
        if !ok {
            println!("  registrar {ours}\n  golden    {golden}");
        }
    };
    check("pubkey", bs58::encode(key.pubkey()).into_string(), &g["registrar_key"]["pubkey"]);
    check("HDreg preimage (111 B)", hex::encode(signed.message), &v["preimage_hex"]);
    check("Ed25519 signature", hex::encode(signed.signature), &v["ed25519_signature_hex"]);
    check("IX_HEADER", hex::encode(IX_HEADER), &g["ed25519_instruction"]["header_hex"]);
    check("Ed25519SigVerify data (223 B)", hex::encode(signed.instruction_data()), &g["ed25519_instruction"]["data_hex"]);
}
