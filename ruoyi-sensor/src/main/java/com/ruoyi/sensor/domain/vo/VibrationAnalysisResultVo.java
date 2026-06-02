package com.ruoyi.sensor.domain.vo;

/**
 * �񶯷���������ض���
 */
public class VibrationAnalysisResultVo
{
    private double rms;
    private double peak;
    private double crestFactor;
    private double kurtosis;
    private double centroidFrequency;
    private double rmsFrequency;
    private double[] frequencyAxis;
    private double[] spectrum;
    private String diagnosis;

    public double getRms() { return rms; }
    public void setRms(double rms) { this.rms = rms; }
    public double getPeak() { return peak; }
    public void setPeak(double peak) { this.peak = peak; }
    public double getCrestFactor() { return crestFactor; }
    public void setCrestFactor(double crestFactor) { this.crestFactor = crestFactor; }
    public double getKurtosis() { return kurtosis; }
    public void setKurtosis(double kurtosis) { this.kurtosis = kurtosis; }
    public double getCentroidFrequency() { return centroidFrequency; }
    public void setCentroidFrequency(double centroidFrequency) { this.centroidFrequency = centroidFrequency; }
    public double getRmsFrequency() { return rmsFrequency; }
    public void setRmsFrequency(double rmsFrequency) { this.rmsFrequency = rmsFrequency; }
    public double[] getFrequencyAxis() { return frequencyAxis; }
    public void setFrequencyAxis(double[] frequencyAxis) { this.frequencyAxis = frequencyAxis; }
    public double[] getSpectrum() { return spectrum; }
    public void setSpectrum(double[] spectrum) { this.spectrum = spectrum; }
    public String getDiagnosis() { return diagnosis; }
    public void setDiagnosis(String diagnosis) { this.diagnosis = diagnosis; }
}
