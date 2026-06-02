-- TDengine ��ʼ���ű�
-- ���������ȷ�����ϵͳ��/Ƶ������д��

CREATE DATABASE IF NOT EXISTS sensor_db KEEP 365 DURATION 1 BUFFER 256 WAL_LEVEL 1;
USE sensor_db;

-- ԭʼ���γ�����
CREATE STABLE IF NOT EXISTS sensor_raw_wave_st (
    ts TIMESTAMP,
    sample_rate INT,
    sample_index INT,
    waveform DOUBLE
) TAGS (
    device_code NCHAR(64)
);

-- Ƶ��㳬����
CREATE STABLE IF NOT EXISTS sensor_fft_point_st (
    ts TIMESTAMP,
    freq_bin INT,
    amplitude DOUBLE
) TAGS (
    device_code NCHAR(64)
);

-- 8 ��ͨ���ӱ���ԭʼ����
CREATE TABLE IF NOT EXISTS sensor_raw_wave_st_ch1 USING sensor_raw_wave_st TAGS ('CH1');
CREATE TABLE IF NOT EXISTS sensor_raw_wave_st_ch2 USING sensor_raw_wave_st TAGS ('CH2');
CREATE TABLE IF NOT EXISTS sensor_raw_wave_st_ch3 USING sensor_raw_wave_st TAGS ('CH3');
CREATE TABLE IF NOT EXISTS sensor_raw_wave_st_ch4 USING sensor_raw_wave_st TAGS ('CH4');
CREATE TABLE IF NOT EXISTS sensor_raw_wave_st_ch5 USING sensor_raw_wave_st TAGS ('CH5');
CREATE TABLE IF NOT EXISTS sensor_raw_wave_st_ch6 USING sensor_raw_wave_st TAGS ('CH6');
CREATE TABLE IF NOT EXISTS sensor_raw_wave_st_ch7 USING sensor_raw_wave_st TAGS ('CH7');
CREATE TABLE IF NOT EXISTS sensor_raw_wave_st_ch8 USING sensor_raw_wave_st TAGS ('CH8');

-- 8 ��ͨ���ӱ���FFT ��
CREATE TABLE IF NOT EXISTS sensor_fft_point_st_ch1 USING sensor_fft_point_st TAGS ('CH1');
CREATE TABLE IF NOT EXISTS sensor_fft_point_st_ch2 USING sensor_fft_point_st TAGS ('CH2');
CREATE TABLE IF NOT EXISTS sensor_fft_point_st_ch3 USING sensor_fft_point_st TAGS ('CH3');
CREATE TABLE IF NOT EXISTS sensor_fft_point_st_ch4 USING sensor_fft_point_st TAGS ('CH4');
CREATE TABLE IF NOT EXISTS sensor_fft_point_st_ch5 USING sensor_fft_point_st TAGS ('CH5');
CREATE TABLE IF NOT EXISTS sensor_fft_point_st_ch6 USING sensor_fft_point_st TAGS ('CH6');
CREATE TABLE IF NOT EXISTS sensor_fft_point_st_ch7 USING sensor_fft_point_st TAGS ('CH7');
CREATE TABLE IF NOT EXISTS sensor_fft_point_st_ch8 USING sensor_fft_point_st TAGS ('CH8');
