from math import log
import time
import subprocess
import logging
import os
import random

# 设置环境变量，确保Cron Job能正确运行
if 'PYTHONPATH' not in os.environ:
    os.environ['PYTHONPATH'] = os.path.dirname(os.path.abspath(__file__))

from TTTest_video_distribute_dev import *
from TTTest_create_dev import *
from TTTest_browse_video_main_dev import *
from TTTest_get_data import *
from TTTest_download_video import main_download_interface
from TTTest_video_info_insert import process_tiktok_data_file
from TTTest_follow_back import get_follow_back_mian
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import pymysql
from TT_fast_follow_0902 import fast_follow

sys.path.insert(0, '/data/appium/com_tiktok_lite_go')
from TT_fast_follow import fast_follow as fast_follow_lite

# 设置日志
today = datetime.now().strftime("%Y%m%d")
# 设置日志文件路径为指定目录
log_dir = '/data/appium/com_zhiliaoapp_musically/zl/log/TTTest_main_auto_2_log'
# 确保目录存在
if not os.path.exists(log_dir):
    os.makedirs(log_dir)
log_filename = log_dir + f"/TTTest_main_auto_2_{today}.log"

# 额外增加一个日志目录与文件：仅记录错误日志（ERROR+）
extra_log_dir = '/data/appium/com_zhiliaoapp_musically/zl/log/TTTest_main_auto_2_log_extra'
if not os.path.exists(extra_log_dir):
    os.makedirs(extra_log_dir)
error_log_filename = extra_log_dir + f"/TTTest_main_auto_2_error_{today}.log"

# 任务结果日志目录与文件：记录任务成功失败状态
result_log_dir = '/data/appium/com_zhiliaoapp_musically/zl/log/TTTest_task_result_2_log'
if not os.path.exists(result_log_dir):
    os.makedirs(result_log_dir)
result_log_filename = result_log_dir + f"/TTTest_task_result_2_{today}.log"

# 配置日志格式：
# - 文件1(log_filename)：记录所有日志（INFO+）
# - 文件2(error_log_filename)：只记录错误及以上（ERROR+）
# - 控制台：INFO+
formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')

def get_executed_phone_ids():
    """
    从今天的任务结果日志中获取已执行的手机ID列表
    
    Returns:
        set: 已执行的手机ID集合
    """
    executed_ids = set()
    
    if not os.path.exists(result_log_filename):
        return executed_ids
    
    try:
        with open(result_log_filename, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                
                parts = line.split(' - ')
                if len(parts) >= 3:
                    phone_id = parts[1]
                    status = parts[2]
                    # 只记录成功和失败的任务，跳过程序开始结束记录
                    if phone_id != 'PROGRAM' and status in ['SUCCESS', 'FAILED', 'ERROR', 'LOG_OUT', 'FOLLOW_SUCCESS', 'FOLLOW_FAILED', 'FOLLOW_ALL', 'FOLLOW_ERROR', 'BLACK_LIST', 'IP_FAILED']:
                        executed_ids.add(phone_id)
    except Exception as e:
        # 使用print而不是logger，因为logger可能还未初始化
        print(f"读取已执行任务日志失败: {e}")
    
    return executed_ids

def log_task_result(phone_id, status):
    """
    记录任务执行结果到专门的日志文件
    
    Args:
        phone_id: 手机ID
        status: 任务状态 ('SUCCESS', 'FAILED', 'ERROR', 'LOG_OUT', etc.)
    """
    try:
        # 如果状态是LOG_OUT，需要检查是否已经记录了其他状态，如果是则覆盖
        if status == 'LOG_OUT':
            # 读取现有日志文件，检查是否已经记录了该phone_id的其他状态
            if os.path.exists(result_log_filename):
                with open(result_log_filename, 'r', encoding='utf-8') as f:
                    lines = f.readlines()
                
                # 从后往前查找该phone_id的最后一条记录
                for i in range(len(lines) - 1, -1, -1):
                    line = lines[i].strip()
                    if not line:
                        continue
                    parts = line.split(' - ')
                    if len(parts) >= 3 and parts[1] == phone_id:
                        # 如果最后一条记录不是LOG_OUT，则覆盖
                        if parts[2] != 'LOG_OUT':
                            # 删除最后一条记录，添加LOG_OUT记录
                            lines[i] = f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - {phone_id} - {status}\n"
                            with open(result_log_filename, 'w', encoding='utf-8') as f:
                                f.writelines(lines)
                            return
                        else:
                            # 如果已经是LOG_OUT，则不重复记录
                            return
        
        # 正常记录新状态
        result_line = f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - {phone_id} - {status}\n"
        
        with open(result_log_filename, 'a', encoding='utf-8') as f:
            f.write(result_line)
            
    except Exception as e:
        # 使用print而不是logger，因为logger可能还未初始化
        print(f"记录任务结果失败: {e}")


def summarize_current_run_results():
    """
    统计本次运行（从最近一次 PROGRAM - START 到 PROGRAM - END 或文件末尾）产生的结果数量。

    返回:
        Dict[str, int]: {
            'TOTAL': int,
            'BLACK_LIST': int,
            'LOG_OUT': int,
            'IP_FAILED': int,
            'FOLLOW_FAILED': int,
            'FOLLOW_SUCCESS': int,
            'FOLLOW_ALL': int,
        }
    """
    summary = {
        'TOTAL': 0,
        'BLACK_LIST': 0,
        'LOG_OUT': 0,
        'IP_FAILED': 0,
        'FOLLOW_FAILED': 0,
        'FOLLOW_SUCCESS': 0,
        'FOLLOW_ALL': 0,
        # 下面四项用于返回对应的 phone_id 列表（去重后）
        'BLACK_LIST_IDS': [],
        'IP_FAILED_IDS': [],
        'LOG_OUT_IDS': [],
        'FOLLOW_FAILED_IDS': [],
        'FOLLOW_SUCCESS_IDS': [],
        'FOLLOW_ALL_IDS': [],
    }
    # 去重统计集合
    unique_ids = set()
    status_sets = {
        'BLACK_LIST': set(),
        'LOG_OUT': set(),
        'FOLLOW_FAILED': set(),
        'FOLLOW_SUCCESS': set(),
        'FOLLOW_ALL': set(),
        'IP_FAILED': set(),
    }
    try:
        lines = []
        with open(result_log_filename, 'r', encoding='utf-8') as f:
            lines = [ln.strip() for ln in f if ln.strip()]

        # 定位最近一次 PROGRAM START 的起始下标
        start_idx = -1
        for idx in range(len(lines) - 1, -1, -1):
            parts = lines[idx].split(' - ')
            if len(parts) >= 3 and parts[1] == 'PROGRAM' and parts[2] == 'START':
                start_idx = idx
                break

        if start_idx == -1:
            return summary

        # 统计从 start_idx 之后到下一个 PROGRAM END（或文件末尾）之间的状态
        for j in range(start_idx + 1, len(lines)):
            parts = lines[j].split(' - ')
            if len(parts) < 3:
                continue
            phone_id = parts[1]
            status = parts[2]
            if phone_id == 'PROGRAM' and status == 'END':
                break

            # 只统计非 PROGRAM 的记录
            if phone_id != 'PROGRAM':
                unique_ids.add(phone_id)
                if status in status_sets:
                    status_sets[status].add(phone_id)

        # 汇总去重结果（数量 + 对应 phone_id 列表）
        summary['TOTAL'] = len(unique_ids)
        for k in status_sets:
            summary[k] = len(status_sets[k])
            # 排序保证输出稳定
            summary[f"{k}_IDS"] = sorted(status_sets[k])
        return summary
    except Exception as e:
        # 统计失败不影响主流程
        try:
            logger.error(f"统计运行结果失败: {e}")
        except Exception:
            pass
        return summary


def format_summary_for_log(summary: dict) -> str:
    """
    将统计结果格式化为多行、可读性更强的日志文本。
    """
    def join_ids(key: str) -> str:
        ids = summary.get(key, []) or []
        # 控制长度，避免日志过长；超过50个仅展示前50个
        max_show = 50
        if len(ids) > max_show:
            shown = ", ".join(ids[:max_show])
            return f"[{shown}, ... 共{len(ids)}个]"
        return f"[{', '.join(ids)}]" if ids else "[]"

    lines = [
        "本次运行统计:",
        f"- 总数(TOTAL): {summary.get('TOTAL', 0)}",
        f"- 冷却(BLACK_LIST): {summary.get('BLACK_LIST', 0)}",
        f"  设备: {join_ids('BLACK_LIST_IDS')}",
        f"- 封号(LOG_OUT): {summary.get('LOG_OUT', 0)}",
        f"  设备: {join_ids('LOG_OUT_IDS')}",
        f"- IP检查失败(IP_FAILED): {summary.get('IP_FAILED', 0)}",
        f"  设备: {join_ids('IP_FAILED_IDS')}",
        f"- 无法关注(FOLLOW_FAILED): {summary.get('FOLLOW_FAILED', 0)}",
        f"  设备: {join_ids('FOLLOW_FAILED_IDS')}",
        f"- 关注成功(FOLLOW_SUCCESS): {summary.get('FOLLOW_SUCCESS', 0)}",
        f"  设备: {join_ids('FOLLOW_SUCCESS_IDS')}",
        f"- 关注全部(FOLLOW_ALL): {summary.get('FOLLOW_ALL', 0)}",
        f"  设备: {join_ids('FOLLOW_ALL_IDS')}",
    ]
    return "\n".join(lines)


def write_summary_to_result_log(summary: dict):
    """
    将统计结果以结构化形式写入 result_log_filename 日志文件，便于后续解析。
    采用简单的 KEY - VALUE 文本行格式。
    """
    try:
        lines = []
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - TOTAL={summary.get('TOTAL',0)}\n")
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - BLACK_LIST={summary.get('BLACK_LIST',0)}\n")
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - LOG_OUT={summary.get('LOG_OUT',0)}\n")
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - IP_FAILED={summary.get('IP_FAILED',0)}\n")
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - FOLLOW_FAILED={summary.get('FOLLOW_FAILED',0)}\n")
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - FOLLOW_SUCCESS={summary.get('FOLLOW_SUCCESS',0)}\n")
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - FOLLOW_ALL={summary.get('FOLLOW_ALL',0)}\n")
        # ID 列表写入（逗号分隔）
        def join_ids(ids):
            return ",".join(ids) if ids else ""
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - BLACK_LIST_IDS={join_ids(summary.get('BLACK_LIST_IDS',[]))}\n")
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - LOG_OUT_IDS={join_ids(summary.get('LOG_OUT_IDS',[]))}\n")
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - IP_FAILED_IDS={join_ids(summary.get('IP_FAILED_IDS',[]))}\n")
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - FOLLOW_FAILED_IDS={join_ids(summary.get('FOLLOW_FAILED_IDS',[]))}\n")
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - FOLLOW_SUCCESS_IDS={join_ids(summary.get('FOLLOW_SUCCESS_IDS',[]))}\n")
        lines.append(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - SUMMARY - FOLLOW_ALL_IDS={join_ids(summary.get('FOLLOW_ALL_IDS',[]))}\n")
        with open(result_log_filename, 'a', encoding='utf-8') as f:
            f.writelines(lines)
    except Exception as e:
        try:
            logger.error(f"写入运行统计到结果日志失败: {e}")
        except Exception:
            pass

all_handler = logging.FileHandler(log_filename, encoding='utf-8')
all_handler.setLevel(logging.INFO)
all_handler.setFormatter(formatter)

error_handler = logging.FileHandler(error_log_filename, encoding='utf-8')
error_handler.setLevel(logging.ERROR)
error_handler.setFormatter(formatter)

console_handler = logging.StreamHandler()
console_handler.setLevel(logging.INFO)
console_handler.setFormatter(formatter)

logging.basicConfig(
    level=logging.INFO,
    handlers=[all_handler, error_handler, console_handler],
    force=True  # 强制重新配置，覆盖其他模块的配置
)

logger = logging.getLogger(__name__)
logger.info(f"程序开始执行，日志文件：{log_filename}")

def TTTest_shell(cmd_str, phone_server_id="10.7.107.224", timeout=180):
    """
    执行SSH命令，支持超时控制
    Args:
        cmd_str: 要执行的命令
        phone_server_id: 服务器IP地址
        timeout: 超时时间（秒），默认5分钟
    Returns:
        tuple: (out, err) 输出和错误信息
    """
    cmd_str = f"ssh ubuntu@{phone_server_id} " + f'{cmd_str}'
    p = None
    try:
        p = subprocess.Popen(cmd_str, shell=True,
                             stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE,
                             encoding='utf-8',
                             text=True, bufsize=1)
        out, err = p.communicate(timeout=timeout)
        return out, err
    except subprocess.TimeoutExpired:
        logger.warning(f"命令执行超时 ({timeout}秒): {cmd_str}")
        if p:
            p.kill()  # 强制终止进程
            p.wait()  # 等待进程完全终止
        return "", f"命令执行超时 ({timeout}秒)"
    except Exception as e:
        logger.error(f"执行命令时出错: {e}")
        if p:
            try:
                p.kill()  # 尝试终止进程
                p.wait()
            except:
                pass
        return "", f"执行命令异常: {str(e)}"


def TTTest_shell2(cmd_str, phone_server_id, timeout=180):
    """
    执行SSH命令，支持超时控制，返回退出码
    Args:
        cmd_str: 要执行的命令
        phone_server_id: 服务器IP地址
        timeout: 超时时间（秒），默认5分钟
    Returns:
        tuple: (out, err, returncode) 输出、错误信息和退出码
    """
    cmd_str = f"ssh ubuntu@{phone_server_id} " + f'{cmd_str}'
    p = None
    try:
        p = subprocess.Popen(cmd_str, shell=True,
                             stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE,
                             encoding='utf-8',
                             text=True, bufsize=1)
        out, err = p.communicate(timeout=timeout)
        return out, err, p.returncode
    except subprocess.TimeoutExpired:
        logger.warning(f"命令执行超时 ({timeout}秒): {cmd_str}")
        if p:
            p.kill()  # 强制终止进程
            p.wait()  # 等待进程完全终止
        return "", f"命令执行超时 ({timeout}秒)", -1
    except Exception as e:
        logger.error(f"执行命令时出错: {e}")
        if p:
            try:
                p.kill()  # 尝试终止进程
                p.wait()
            except:
                pass
        return "", f"执行命令异常: {str(e)}", -1

# 数据库配置
DB_CONFIG = {
    'db_host': '10.7.43.162',  # 实际的数据库地址
    'db_port': 3306,           # 数据库端口
    'db_user': 'root',
    'db_pass': 'Wumitech',
    'db_name': 'tt'
}

def get_database_connection():
    """
    创建数据库连接
    Returns:
        pymysql.Connection: 数据库连接对象
    """
    try:
        logger.info(f"尝试连接数据库...")
        logger.info(f"主机: {DB_CONFIG['db_host']}")
        logger.info(f"端口: {DB_CONFIG['db_port']}")
        logger.info(f"用户: {DB_CONFIG['db_user']}")
        logger.info(f"数据库: {DB_CONFIG['db_name']}")

        connection = pymysql.connect(
            host=DB_CONFIG['db_host'],
            port=DB_CONFIG['db_port'],
            user=DB_CONFIG['db_user'],
            password=DB_CONFIG['db_pass'],
            database=DB_CONFIG['db_name'],
            connect_timeout=20
        )
        logger.info("数据库连接成功！")
        return connection
    except Exception as e:
        logger.error(f"连接数据库时出错: {e}")
        logger.error("请检查以下内容：")
        logger.error(f"1. 用户名 {DB_CONFIG['db_user']} 是否正确")
        logger.error(f"2. 密码是否正确")
        logger.error(f"3. 该用户是否有权限从远程连接")
        logger.error(f"4. 数据库 {DB_CONFIG['db_name']} 是否存在")
        raise

# 新增：从数据库获取 phone_id 列表及所属服务器IP
def fetch_phone_ids_from_db(connection, upload_status=0, status=0, limit=None):
    """从表 tt_account_data 获取 phone_id 列表及所属服务器IP
    Args:
        connection: 数据库连接
        upload_status: 上传状态
        limit: 可选，限制返回数量（int）
    Returns:
        List[Dict]: { 'phone_id': str, 'phone_server_id': str, 'pkg_name': str } 数组
    """
    # 获取已执行的手机ID列表
    executed_ids = get_executed_phone_ids()
    # 注意：这里不能使用logger，因为logger可能还未初始化
    print(f"已执行的手机ID数量: {len(executed_ids)}")
    if executed_ids:
        print(f"已执行的手机ID: {list(executed_ids)[:10]}...")  # 只显示前10个
    
    results = []
    sql = "SELECT phone_id, phone_server_id, pkg_name FROM tt_account_data WHERE  phone_server_id = '10.7.107.224' and status = %s and upload_status = %s"
    try:
        with connection.cursor() as cursor:
            cursor.execute(sql, (status,upload_status,))
            rows = cursor.fetchall()
            for row in rows:
                # row 可能是元组 (phone_id, server_ip)
                if isinstance(row, (list, tuple)):
                    pid = row[0]
                    sip = row[1] if len(row) > 1 else None
                    pkg = row[2] if len(row) > 2 else None
                else:
                    # 若cursor是dict cursor
                    pid = row.get('phone_id')
                    sip = row.get('phone_server_id')
                    pkg = row.get('pkg_name')
              
                if isinstance(sip, bytes):
                    sip = sip.decode('utf-8', errors='ignore')
                
                phone_id = str(pid)
                
                # 检查是否已经执行过
                if phone_id in executed_ids:
                    print(f"跳过已执行的手机ID: {phone_id}")
                    continue
                if isinstance(pkg, bytes):
                    pkg = pkg.decode('utf-8', errors='ignore')
                # 若数据库中 pkg_name 为空或 NULL，则统一赋值为默认包名
                final_pkg = pkg if (pkg is not None and str(pkg).strip() != '') else 'com.zhiliaoapp.musically'

                results.append({
                    'phone_id': phone_id,
                    'phone_server_id': (str(sip) if sip else ''),
                    'pkg_name': final_pkg
                })
            logger.info(f"从数据库获取到 {len(results)} 个 phone_id（含服务器IP）")
    except Exception as e:
        logger.error(f"查询 phone_id 列表失败: {e}")
        raise
    # 应用 limit（如果需要）
    if isinstance(limit, int) and limit > 0:
        return results[:limit]
    return results

def sub_main(phone_id, phone_server_id, pkg_name, upload_status = 0,video_description_list = None,status = 0):
    time.sleep(random.randint(10, 12))
    try:
        #切换到closeli
        logger.info(f"{phone_id} - {today}: 切换到closeli")
        for i in range(5):
            out, err = TTTest_shell(f"sudo bash /home/ubuntu/tiktok_switch_dynamic_ip_to_closeli.sh  {phone_id}", phone_server_id)
            logger.info(f"{phone_id} - {today}: 切换到closeli结果：out：{out} err：{err}")
            if '切换到CLOSELI完成' in out:
                logger.info(f"{phone_id} - {today}: 切换到closeli成功")
                break
            else:
                logger.error(f"{phone_id} - {today}: 切换到closeli失败,{err}")
                time.sleep(random.randrange(2, 5))
        time.sleep(random.randrange(10, 15))

        # 检查IP地区
        cmd = f"docker exec {phone_id} curl -s  dynamicip.wumitech.com/json | jq .iso_code"
        country, err, code = TTTest_shell2(cmd, phone_server_id)
        if code == 1:
            logger.error(f"{phone_id} - {today}: IP地区检查异常：{err}")

            return False
        if err:
            logger.error(f"{phone_id} - {today}: IP地区检查报错：{err}")
            return False
        # 清理country变量，去除可能的换行符和空格
        country = country.strip().strip('"') if country else ""
        logger.info(f"{phone_id} - {today}: IP地区检查结果：'{country}'")
        # 允许 US 和 CA，其他国家判定为不符
        if country not in ['US', 'CA']:
            logger.error(f"{phone_id} - {today}:ip地区不符， IP地区检查结果：'{country}'，关闭手机")
            TTTest_shell(f"sudo bash /home/ubuntu/tiktok_phone_stop.sh {phone_id}", phone_server_id)
            log_task_result(phone_id, 'IP_FAILED')
            return False
        logger.info(f"{phone_id} - {today}: IP地区检查通过，开始执行后续操作")    
        
        # # 收集数据 收集失败，不影响整体流程
        # try:
        #     logger.info(f"{phone_id} - {today}:收集数据")
        #     get_mian_data(phone_id, phone_server_id)
            
        # except Exception as e:
        #     logger.error(f"{phone_id} - {today}:收集数据,异常：{e}")
        #     logger.error(f"{phone_id} - {today}:异常详情：{str(e)}")

        # 判断是否需要上传视频 视频上传失败，影响整体流程
        if upload_status == 1 and pkg_name == 'com.zhiliaoapp.musically':
            try:
                logger.info(f"{phone_id} - {today}:上传视频")
                for video_description in video_description_list:
                    autoUploadVedio(phone_id, phone_server_id, video_description)
            except Exception as e:
                logger.error(f"{phone_id} - {today}:上传视频,异常：{e}")
                raise
        # 定义两个任务函数
        def follow_task():
            """关注任务"""
            if status == 0:
                try:
                    logger.info(f"{phone_id} - {today}:关注脚本")
                    if pkg_name == 'com.tiktok.lite.go':
                        logger.info(f"{phone_id} - {today}:关注脚本 lite")
                        ret = fast_follow_lite(phone_id, phone_server_id)
                    else:
                        logger.info(f"{phone_id} - {today}:关注脚本 musically")
                        ret = fast_follow(phone_id, phone_server_id)
                    if ret == 1:
                        log_task_result(phone_id, 'FOLLOW_SUCCESS')
                    elif ret == 0:
                        log_task_result(phone_id, 'FOLLOW_FAILED')
                    elif ret == 2:
                        log_task_result(phone_id, 'FOLLOW_ALL')
                    else:
                        log_task_result(phone_id, 'FOLLOW_ERROR')
                    logger.info(f"{phone_id} - {today}- {ret}:关注脚本成功结束")
                    
                except Exception as e:
                    logger.error(f"{phone_id} - {today}:关注,异常：{e}")
            elif status == 2:
                log_task_result(phone_id, 'BLACK_LIST')
        def browse_task():
            """浏览视频任务"""
            for i in range(2):
                try:
                    logger.info(f"{phone_id} - {today}:浏览视频")
                    ret = worker(phone_id, phone_server_id, pkg_name)
                    if ret is False:
                        log_task_result(phone_id, 'LOG_OUT')
                    logger.info(f"{phone_id} - {today}:浏览视频,成功结束")
                    break
                except Exception as e:
                    logger.error(f"{phone_id} - {today}:浏览视频,异常：{e}")

        # 随机决定执行顺序
        tasks = [follow_task,browse_task]
        random.shuffle(tasks)
        
        # 按随机顺序执行任务
        for task in tasks:
            task()

        # # 随机点击回关
        # if random.randint(0, 50) < 1:
        #     try:
        #         logger.info(f"{phone_id} - {today}:随机点击回关")
        #         get_follow_back_mian(phone_id, phone_server_id)
        #     except Exception as e:
        #         logger.error(f"{phone_id} - {today}:随机点击回关,异常：{e}")
        #         logger.error(f"{phone_id} - {today}:异常详情：{str(e)}")
        
        if upload_status == 1:
            # 视频是否上传成功
            is_uploaded = False
            for i in range(3):
                try:
                    logger.info(f"{phone_id} - {today}:检查上传进度")
                    is_uploaded = isuploaded(phone_id, phone_server_id)
                    logger.info(f"{phone_id} - {today}:检查上传进度,成功")
                    break
                except Exception as e:
                    logger.info(f"{phone_id} - {today}:检查上传进度,异常：{e}")
            if is_uploaded:
                logger.info(f"{phone_id} - {today}:视频上传完毕，养号完毕 关闭应用")
                close_app(phone_id, phone_server_id)
                logger.info(f"{phone_id} - {today}:视频上传完毕，养号完毕 关闭手机")
                TTTest_shell(f"sudo bash /home/ubuntu/tiktok_phone_stop.sh {phone_id}",phone_server_id)

            else:
                logger.info(f"{phone_id} - {today}:养号完毕 上传未完成 5分钟后强行关闭")
                time.sleep(300)
                logger.info(f"{phone_id} - {today}:视频上传完毕，养号完毕 关闭应用")
                close_app(phone_id, phone_server_id)
                logger.info(f"{phone_id} - {today}:视频上传完毕，养号完毕 关闭手机")
                TTTest_shell(f"sudo bash /home/ubuntu/tiktok_phone_stop.sh {phone_id}",phone_server_id)
                logger.info(f"{phone_id} - {today}:养号完毕 关闭应用")
                close_app(phone_id, phone_server_id)
                logger.info(f"{phone_id} - {today}:养号完毕 关闭手机")
                TTTest_shell(f"sudo bash /home/ubuntu/tiktok_phone_stop.sh {phone_id}",phone_server_id)
                return True
        else:
            logger.info(f"{phone_id} - {today}:养号完毕 关闭应用")
            close_app(phone_id, phone_server_id)
            logger.info(f"{phone_id} - {today}:养号完毕 关闭手机")
            TTTest_shell(f"sudo bash /home/ubuntu/tiktok_phone_stop.sh {phone_id}",phone_server_id)
            return True
        
    except KeyboardInterrupt:
        logger.error(f"{phone_id} - {today}:收到中断信号，强制清理")
        try:
            close_app(phone_id, phone_server_id)
            TTTest_shell(f"sudo bash /home/ubuntu/tiktok_phone_stop.sh {phone_id}",phone_server_id)
        except:
            pass
        raise
    except Exception as e:
        logger.error(f"{phone_id} - {today}:任务执行异常，强制清理: {e}")
        try:
            close_app(phone_id, phone_server_id)
            TTTest_shell(f"sudo bash /home/ubuntu/tiktok_phone_stop.sh {phone_id}",phone_server_id)
        except:
            pass
        raise
    
def auto_main(phone_id_list, phone_server_ids, pkg_name_list, upload_status = 0,status = 0):

    try:
        for i in range(len(phone_id_list)):
            phone_id = phone_id_list[i]
            phone_server_id = phone_server_ids[i]
            pkg_name = pkg_name_list[i]
            #检查静态ip
            try:
                logger.info(f"{phone_id} - {today}:检查静态ip")
                out, err, code = TTTest_shell2(f"sudo /home/ubuntu/tiktok_check_static_ip.sh {phone_id} US", phone_server_id)
                if code == 1:
                    logger.error(f"{phone_id} - {today}:检查静态ip,异常：{out}")
                logger.info(f"{phone_id} - {today}:检查静态ip结果：out：{out} err：{err}")
            except Exception as e:
                logger.error(f"{phone_id} - {today}:检查静态ip,异常：{out}")
                raise


             # 开启手机
            try:
                logger.info(f"{phone_id} - {today}:开机")
                out, err, code = TTTest_shell2(f"sudo bash /home/ubuntu/tiktok_phone_start.sh {phone_id}", phone_server_id)
                if code == 1:
                    log_task_result(phone_id, 'IP_FAILED')
                    logger.error(f"{phone_id} - {today}:开机,异常：{out}")
                logger.info(f"{phone_id} - {today}:开机结果：out：{out} err：{err}")
            except Exception as e:
                log_task_result(phone_id, 'IP_FAILED')
                logger.error(f"{phone_id} - {today}:开机,异常：{out}")
                raise
        logger.info(f"图片分发-{today}，开启手机,等待20秒")
        time.sleep(20)
        for i in range(len(phone_id_list)):
            phone_id = phone_id_list[i]
            phone_server_id = phone_server_ids[i]
            try:
                for i in range(3):
                    logger.info(f"{phone_id} - {today}:开机检测")
                    out, err = TTTest_shell(f"docker exec {phone_id} getprop sys.boot_completed", phone_server_id)
                    if err:
                        logger.error(f"{phone_id} - {today}:开机检测,异常：{err}")
                    logger.info(f"{phone_id} - {today}:检测结果：out：{out} err：{err}")
                    if '1' in out:
                        logger.info(f"{phone_id} - {today}:开机成功")
                        break
                    time.sleep(8)
            except Exception as e:
                logger.error(f"{phone_id} - {today}:开机,异常：{e}")
                raise
        if upload_status == 1:
            logger.info(f"视频分发-{today}，开始")
            video_distribut_ret = distribute_dev(phone_id_list, phone_server_id,upload_video_count=1, video_start_index=0)
            if len(video_distribut_ret) > 0:
                logger.info(f"视频分发-{today}，成功\n{str(video_distribut_ret)}")
            else:
                logger.info(f"视频分发-{today}，失败")
                raise Exception(f"视频分发-{today}，失败")
    except Exception as e:
        raise

    # 数据采集，视频上传，养号，出错，影响整体流程
    # 最多同时开启10个线程（与每组设备数量一致）
    with ThreadPoolExecutor(max_workers=10) as executor:
        # 提交所有任务并收集future对象
        futures = []
        for i, phone_id in enumerate(phone_id_list):
            phone_server_id = phone_server_ids[i] if i < len(phone_server_ids) and phone_server_ids[i] else "10.7.107.224"
            pkg_name = pkg_name_list[i]
            if upload_status == 1:
                video_description_list = video_distribut_ret[phone_id]
            else:
                video_description_list = None
            future = executor.submit(sub_main, phone_id, phone_server_id, pkg_name, upload_status,video_description_list,status)
            futures.append(future)
        
        # 等待所有任务完成
        logger.info(f"等待所有{len(phone_id_list)}个任务完成...")
        for i, future in enumerate(futures):
            try:
                result = future.result()  # 等待每个任务完成
                
                if result is False:
                    logger.error(f"任务 {phone_id_list[i]} 执行失败（IP地区检查失败）")
                    # log_task_result(phone_id_list[i], 'FAILED')
                else:
                    logger.info(f"任务 {phone_id_list[i]} 执行完成")
                    # log_task_result(phone_id_list[i], 'SUCCESS')
                    
            except Exception as e:
                logger.error(f"任务 {phone_id_list[i]} 执行失败: {e}")
                log_task_result(phone_id_list[i], 'ERROR')
                
                logger.info(f"{phone_id_list[i]} - {today}:强制关闭应用")
                phone_server_id = phone_server_ids[i] if i < len(phone_server_ids) and phone_server_ids[i] else "10.7.107.224"
                close_app(phone_id_list[i], phone_server_id)
                logger.info(f"{phone_id_list[i]} - {today}:强制关闭手机,异常：{e}")
                TTTest_shell(f"sudo bash /home/ubuntu/tiktok_phone_stop.sh {phone_id}",phone_server_id)
        
        logger.info(f"所有{len(phone_id_list)}个任务执行完毕")


if __name__ == "__main__":
    start_time = datetime.now()
    

    # 确保日志目录存在
    try:
        if not os.path.exists(log_dir):
            os.makedirs(log_dir)
        logger.info(f"=== 程序开始执行，开始时间: {start_time.strftime('%Y-%m-%d %H:%M:%S')} ===")
        logger.info(f"当前工作目录: {os.getcwd()}")
        logger.info(f"日志文件: {log_filename}")
        logger.info(f"任务结果日志: {result_log_filename}")
        
        # 记录程序开始信息到结果日志
        log_task_result('PROGRAM', 'START')
        
        # 显示已执行任务统计
        executed_ids = get_executed_phone_ids()
        if executed_ids:
            logger.info(f"今日已执行任务数量: {len(executed_ids)}")
            logger.info(f"已执行任务ID: {sorted(list(executed_ids))}")
        else:
            logger.info("今日暂无已执行任务")
    except Exception as e:
        # 如果日志配置失败，直接写入文件
        with open('/tmp/cron_error.log', 'a', encoding='utf-8') as f:
            f.write(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} - 日志配置失败: {e}\n")
        raise
    
    # 从数据库读取 phone_ids 并发执行（执行两轮）
    for round_index in range(3):
        logger.info(f"=== 开始第{round_index + 1}轮数据库查询与执行 ===")
        connection = None
        try:
            connection = get_database_connection()
            # 第一批执行上传，第二批不执行上传
            if round_index == 0:
                upload_status = 1
                status = 0
            else:
                if round_index == 1:
                    status = 0
                else:
                    status = 2
                    
                # 在非第一轮时，先获取所有符合条件的设备，然后随机选择
                upload_status = 0
            phone_records = fetch_phone_ids_from_db(connection,upload_status,status)
            if not phone_records:
                print("数据库未返回任何 phone_id，退出。")
                phone_id_list_all = []
                phone_server_id_list_all = []
                pkg_name_list_all = []
            else:
                phone_id_list_all = [rec['phone_id'] for rec in phone_records]
                phone_server_id_list_all = [rec['phone_server_id'] for rec in phone_records]
                pkg_name_list_all = [rec.get('pkg_name', '') for rec in phone_records]
                print(f"从数据库获取到 {len(phone_id_list_all)} 个 phone_id，{len(phone_server_id_list_all)} 个服务器IP，{len(pkg_name_list_all)} 个pkg_name")
            logger.info(f"使用的云手机服务器IP: {phone_server_id_list_all}")
            logger.info(f"使用的pkg_name: {pkg_name_list_all}")
        except Exception as e:
            print(f"从数据库加载 phone_id 失败: {e}")
            raise
        finally:
            try:
                if connection:
                    logger.info(f"关闭数据库连接")
                    connection.close()
            except:
                pass

        logger.info(f"第{round_index + 1}轮 - 总共需要处理的设备: {len(phone_id_list_all)}个")
        logger.info(f"第{round_index + 1}轮 - 设备列表: {phone_id_list_all}")
        
        # randowUploadNums = random.sample(range(30), 5)
        # logger.info(f"随机上传设备数量: {randowUploadNums}")
        # 设备，10个一组执行，每组最多30分钟
        for i in range(0, len(phone_id_list_all), 10):
            group = phone_id_list_all[i:i+10]
            phone_server_id_group = phone_server_id_list_all[i:i+10]
            pkg_name_group = pkg_name_list_all[i:i+10] if 'pkg_name_list_all' in locals() else []
            group_start_time = datetime.now()
            logger.info(f"=== 第{round_index + 1}轮开始执行第{i//10 + 1}组: {group} ===")
            logger.info(f"第{round_index + 1}轮第{i//10 + 1}组开始时间: {group_start_time.strftime('%Y-%m-%d %H:%M:%S')}")
            logger.info(f"第{round_index + 1}轮第{i//10 + 1}组超时限制: 30分钟")
            
            # 使用线程池执行任务，设置超时
            with ThreadPoolExecutor(max_workers=1) as executor:
                if round_index == 0:
                    upload_status = 1
                else:
                    # 随机决定是否上传视频 (30% 概率上传)
                    if random.randint(1, 100) <= 30:
                        upload_status = 1
                        logger.info(f"第{round_index + 1}轮第{i//10 + 1}组: 随机选择上传视频")
                    else:
                        upload_status = 0
                        logger.info(f"第{round_index + 1}轮第{i//10 + 1}组: 随机选择不上传视频")
                future = executor.submit(auto_main, group, phone_server_id_group, pkg_name_group, upload_status,status)
                try:
                    # 等待30分钟（1800秒）
                    future.result(timeout=2400)
                    group_end_time = datetime.now()
                    group_duration = group_end_time - group_start_time
                    logger.info(f"=== 第{round_index + 1}轮第{i//10 + 1}组执行完成，耗时: {group_duration} ===")
                except TimeoutError:
                    group_end_time = datetime.now()
                    group_duration = group_end_time - group_start_time
                    logger.error(f"=== 第{round_index + 1}轮第{i//10 + 1}组执行超时（30分钟），耗时: {group_duration} ===")
                    logger.error(f"=== 强制关闭第{round_index + 1}轮第{i//10 + 1}组所有设备 ===")
                    # 强制关闭该组所有设备
                    for idx, phone_id in enumerate(group):
                        phone_server_id = phone_server_id_group[idx] if idx < len(phone_server_id_group) and phone_server_id_group[idx] else "10.7.107.224"
                        try:
                            logger.info(f"{phone_id} - {today}:超时强制关闭应用")
                            close_app(phone_id, phone_server_id)
                            logger.info(f"{phone_id} - {today}:超时强制关闭手机")
                            TTTest_shell(f"sudo bash /home/ubuntu/tiktok_phone_stop.sh {phone_id}", phone_server_id)
                        except Exception as e:
                            logger.error(f"关闭设备 {phone_id} 失败: {e}")
                except Exception as e:
                    group_end_time = datetime.now()
                    group_duration = group_end_time - group_start_time
                    logger.error(f"=== 第{round_index + 1}轮第{i//10 + 1}组执行失败，耗时: {group_duration}，错误: {e} ===")
                    # 发生异常时也关闭设备
                    logger.error(f"=== 强制关闭第{round_index + 1}轮第{i//10 + 1}组所有设备 ===")
                    for idx, phone_id in enumerate(group):
                        phone_server_id = phone_server_id_group[idx] if idx < len(phone_server_id_group) and phone_server_id_group[idx] else "10.7.107.224"
                        try:
                            logger.info(f"{phone_id} - {today}:强制关闭应用")
                            close_app(phone_id, phone_server_id)
                            logger.info(f"{phone_id} - {today}:强制关闭手机")
                            TTTest_shell(f"sudo bash /home/ubuntu/tiktok_phone_stop.sh {phone_id}", phone_server_id)
                        except Exception as close_e:
                            logger.error(f"关闭设备 {phone_id} 失败: {close_e}")
            
            # 组间等待，避免资源冲突
            if i < len(phone_id_list_all) - 10:  # 不是最后一组
                logger.info(f"第{round_index + 1}轮第{i//10 + 1}组执行完毕，等待30秒后开始下一组...")
                time.sleep(30)
        
        # 两轮之间适当等待
        if round_index == 0:
            logger.info("第1轮完成，等待30秒后开始第2轮...")
            time.sleep(30)
    
    end_time = datetime.now()
    total_duration = end_time - start_time
    logger.info(f"=== 所有任务执行完成，总耗时: {total_duration} ===")
    logger.info(f"=== 程序结束时间: {end_time.strftime('%Y-%m-%d %H:%M:%S')} ===")
    
    # 统计本次运行的关键数据并输出
    try:
        summary = summarize_current_run_results()
        logger.info("\n" + format_summary_for_log(summary))
        # 同步写入结果日志文件，便于后续检索
        write_summary_to_result_log(summary)
    except Exception as e:
        logger.error(f"输出/写入本次运行统计失败: {e}")

    # 记录程序结束信息到结果日志
    log_task_result('PROGRAM', 'END')
    try:
        logger.info("开始处理 TikTok 数据文件（自动插入数据库）...")
        process_tiktok_data_file()
        logger.info("TikTok 数据文件处理完成！")
    except Exception as e:
        logger.error(f"处理 TikTok 数据文件时出错: {e}")