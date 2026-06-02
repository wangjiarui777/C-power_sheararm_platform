const payload = {
  deviceCode: 'DEV-001',
  vibrationValue: 0.18,
  temperatureValue: 36.9,
  sampleTime: new Date().toISOString().slice(0, 19).replace('T', ' '),
  collectionTime: new Date().toISOString().slice(0, 19).replace('T', ' '),
  remark: 'collector upload'
}

async function uploadOnce() {
  await fetch('http://localhost:80/system/vibration/upload', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      deviceCode: payload.deviceCode,
      vibrationValue: payload.vibrationValue,
      sampleTime: payload.sampleTime,
      remark: payload.remark
    })
  })

  await fetch('http://localhost:80/system/temperature/upload', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      deviceCode: payload.deviceCode,
      temperatureValue: payload.temperatureValue,
      collectionTime: payload.collectionTime,
      remark: payload.remark
    })
  })
}

setInterval(() => {
  uploadOnce().catch(console.error)
}, 5000)
