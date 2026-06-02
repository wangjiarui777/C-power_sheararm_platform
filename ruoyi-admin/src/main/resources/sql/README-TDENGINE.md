# TDengine ��ʼ��˵��

## 1. ������Ϣ

��������Ĭ�����ã�

- URL��`jdbc:TAOS-RS://localhost:6041/sensor_db`
- �û�����`root`
- ���룺`taosdata`

## 2. ִ��˳��

�ȵ�¼ TDengine �ͻ��ˣ�ִ�У�

```sql
CREATE DATABASE IF NOT EXISTS sensor_db;
USE sensor_db;
```

Ȼ��ִ�� `tdengine-init.sql`�������Զ�������

- ԭʼ���γ����� `sensor_raw_wave_st`
- Ƶ��㳬���� `sensor_fft_point_st`
- 8 ��ͨ���ӱ�

## 3. �ӱ�·�ɹ���

��˻ᰴ `channelId` �̶�д�룺

- `1 -> sensor_raw_wave_st_ch1`
- `2 -> sensor_raw_wave_st_ch2`
- ...
- `8 -> sensor_raw_wave_st_ch8`

FFT ��ͬ����

## 4. ��������

### Database not specified

˵����ִ�н���ǰû���� `USE sensor_db;`������ JDBC URL δָ�� `sensor_db`��

### Table not exist

˵�� `tdengine-init.sql` ��û��ִ�У���ִ��ʧ�ܡ�
