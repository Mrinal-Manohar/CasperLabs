use common::bytesrepr::{deserialize, ToBytes};
use common::key::Key;
use common::value::Value;
use error::Error;
use gs::DbReader;
use gs::{GlobalState, TrackingCopy};
use rkv::store::single::SingleStore;
use rkv::{Manager, Rkv, StoreOptions};
use std::fmt;
use std::path::Path;
use std::sync::{Arc, RwLock};
use transform::Transform;

pub struct LmdbGs {
    store: SingleStore,
    env: Arc<RwLock<Rkv>>,
}

impl LmdbGs {
    pub fn new(p: &Path) -> Result<LmdbGs, Error> {
        let env = Manager::singleton()
            .write()
            .map_err(|_| Error::RkvError)
            .and_then(|mut r| r.get_or_create(p, Rkv::new).map_err(|e| e.into()))?;
        let store = env.read().map_err(|_| Error::RkvError).and_then(|r| {
            r.open_single(Some("global_state"), StoreOptions::create())
                .map_err(|e| e.into())
        })?;
        Ok(LmdbGs { store, env })
    }

    pub fn read(&self, k: &Key) -> Result<Value, Error> {
        self.env
            .read()
            .map_err(|_| Error::RkvError)
            .and_then(|rkv| {
                let r = rkv.read()?;
                let maybe_curr = self.store.get(&r, k)?;

                match maybe_curr {
                    None => Err(Error::KeyNotFound { key: *k }),
                    Some(rkv::Value::Blob(bytes)) => {
                        let value = deserialize(bytes)?;
                        Ok(value)
                    }
                    //If we always store values as Blobs this case will never come
                    //up. TODO: Use other variants of rkb::Value (e.g. I64, Str)?
                    Some(_) => Err(Error::RkvError),
                }
            })
    }

    pub fn write<'a, I>(&self, mut kvs: I) -> Result<(), Error>
    where
        I: Iterator<Item = (Key, &'a Value)>,
    {
        self.env
            .read()
            .map_err(|_| Error::RkvError)
            .and_then(|rkv| {
                let mut w = rkv.write()?;

                let result: Result<(), Error> = kvs.try_fold((), |_, (k, v)| {
                    let bytes = v.to_bytes();
                    let _ = self.store.put(&mut w, k, &rkv::Value::Blob(&bytes))?;
                    Ok(())
                });

                match result {
                    Ok(_) => {
                        let _ = w.commit()?;
                        Ok(())
                    }
                    e @ Err(_) => {
                        w.abort();
                        e
                    }
                }
            })
    }

    pub fn write_single(&self, k: Key, v: &Value) -> Result<(), Error> {
        let iterator = std::iter::once((k, v));
        self.write(iterator)
    }
}

impl DbReader for LmdbGs {
    fn get(&self, k: &Key) -> Result<Value, Error> {
        //TODO: The `Reader` should really be static for the DbReader instance,
        //i.e. just by creating a DbReader for LMDB it should create a `Reader`
        //to go with it. This would prevent the database from being modified while
        //another process was expecing to be able to read it.
        self.read(k)
    }
}

impl GlobalState for LmdbGs {
    fn apply(&mut self, k: Key, t: Transform) -> Result<(), Error> {
        let maybe_curr = self.get(&k);
        match maybe_curr {
            Err(Error::KeyNotFound { .. }) => match t {
                Transform::Write(v) => self.write_single(k, &v),
                _ => Err(Error::KeyNotFound { key: k }),
            },
            Ok(curr) => {
                let new_value = t.apply(curr)?;
                self.write_single(k, &new_value)
            }
            Err(e) => Err(e),
        }
    }
    fn tracking_copy(&self) -> Result<TrackingCopy<Self>, Error> {
        Ok(TrackingCopy::new(self))
    }
}

impl fmt::Debug for LmdbGs {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "LMDB({:?})", self.env)
    }
}

#[cfg(test)]
mod tests {
    use gens::gens::*;
    use gs::lmdb::LmdbGs;
    use tempfile::tempdir;

    #[test]
    fn lmdb_new() {
        //Note: the directory created by `temp_dir`
        //is automatically deleted when it goes out of scope.
        let temp_dir = tempdir().unwrap();
        let path = temp_dir.path();
        let lmdb = LmdbGs::new(&path);

        assert_matches!(lmdb, Ok(_));
    }

    #[test]
    fn lmdb_rw() {
        let temp_dir = tempdir().unwrap();
        let path = temp_dir.path();
        let lmdb = LmdbGs::new(&path).unwrap();

        proptest!(|(k in key_arb(), v in value_arb())| {
          let write = lmdb.write_single(k, &v);
          let read = lmdb.read(&k);
          assert_matches!(write, Ok(_));
          prop_assert_eq!(read, Ok(v.clone()));
        });
    }
}