import scipy.io

# 加载 mat 文件
data = scipy.io.loadmat('CH1_20260515_085738_sr7497_rpm3000_UN_7500.mat')

# 提取特定变量
matrix_data = data['variable_name']