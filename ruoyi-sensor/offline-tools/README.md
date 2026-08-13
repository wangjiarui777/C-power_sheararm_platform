# Legacy object NPY conversion

This directory is excluded from online inference images. Run the converter only
inside a disposable, low-privilege container with networking disabled. Treat the
source as arbitrary code because NumPy Pickle loading is enabled for this single
offline migration step.

Example:

```powershell
$env:LEGACY_NPY_OFFLINE_MODE='1'
python convert_legacy_object_npy.py old.npy clean.npy --manifest clean.sha256.json --acknowledge I_UNDERSTAND_PICKLE_IS_UNSAFE
```

Copy only the converted `.npy`/`.npz` and its manifest into the controlled import
area. Never copy this directory or the legacy source into the service image.
