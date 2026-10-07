import subprocess
import time
import socket
import json
import os
import statistics

JAVA_EXE = r'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe'
JAR_PATH = r'build\libs\pop-gateway-0.0.1-SNAPSHOT.jar'
K6_EXE = r'.\k6.exe'

def kill_gateway():
    try:
        subprocess.run(['powershell', '-Command', 'Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue'], check=False)
    except Exception as e:
        print(f'Error killing gateway: {e}', flush=True)

def flush_redis():
    try:
        subprocess.run(['docker', 'exec', 'pop-mesh-redis-1', 'redis-cli', 'FLUSHALL'], check=False, stdout=subprocess.DEVNULL)
    except Exception as e:
        print(f'Error flushing redis: {e}', flush=True)

def wait_for_port(port=8443, timeout=30):
    start = time.time()
    while time.time() - start < timeout:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(0.5)
            if s.connect_ex(('127.0.0.1', port)) == 0:
                return True
        time.sleep(0.5)
    return False

def run_benchmark_cycle(cycle):
    print(f'\n========================================', flush=True)
    print(f'   STARTING BENCHMARK CYCLE {cycle} / 5', flush=True)
    print(f'========================================\n', flush=True)
    
    # 1. Kill and flush
    print('[Step 1] Resetting Gateway and flushing L1/L2 caches...', flush=True)
    kill_gateway()
    flush_redis()
    time.sleep(3)
    
    # Start fresh JVM
    print('[Step 1] Booting fresh Gateway process...', flush=True)
    proc = subprocess.Popen([JAVA_EXE, '-jar', JAR_PATH], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    
    if not wait_for_port(8443, timeout=30):
        print(f'FATAL: Gateway failed to bind port 8443 on cycle {cycle}!', flush=True)
        proc.kill()
        raise RuntimeError('Gateway boot timeout')
    
    print(f'[Step 1] Gateway is ready on port 8443! Waiting 2s for warmup initialization...', flush=True)
    time.sleep(2)
    
    scenarios = [
        ('scenario_a_cold', 'scenario_a_l1_warm', f'scenario_a_cold_run{cycle}.json', 'Scenario A (Cold Start, 30s)'),
        ('scenario_a_warmed', 'scenario_a_l1_warm', f'scenario_a_warmed_run{cycle}.json', 'Scenario A (Warmed-up, 30s)'),
        ('scenario_b_warmed', 'scenario_b_l2_cold', f'scenario_b_warmed_run{cycle}.json', 'Scenario B (L2 Cold, Warmed-up, 30s)'),
        ('scenario_c_stress', 'scenario_c_stress', f'scenario_c_stress_run{cycle}.json', 'Scenario C (500 VU Stress, 120s)')
    ]
    
    for idx, (name, target, out_file, desc) in enumerate(scenarios, start=2):
        print(f'\n[Step {idx}] Executing {desc} -> {out_file}...', flush=True)
        cmd = [
            K6_EXE, 'run', 'benchmark-suite.js',
            '-e', f'TARGET_SCENARIO={target}',
            '--summary-trend-stats=min,avg,med,p(90),p(95),p(99),max',
            f'--summary-export={out_file}'
        ]
        subprocess.run(cmd, check=True)
        print(f'[Step {idx}] {desc} finished. Output saved to {out_file}.', flush=True)
        
        # 10s idle between runs
        if idx < len(scenarios) + 1:
            print('[Idle] Pausing 10s to cool down network & buffers...', flush=True)
            time.sleep(10)
            
    # Terminate gateway at end of cycle
    print(f'[Step 6] Shutting down Gateway process for cycle {cycle}...', flush=True)
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except:
        proc.kill()
    kill_gateway()
    print(f'Cycle {cycle} complete! Waiting 10s before next cycle...\n', flush=True)
    time.sleep(10)

def aggregate_results():
    print('\n========================================', flush=True)
    print('   AGGREGATING 5 RUNS FOR ALL SCENARIOS', flush=True)
    print('========================================\n', flush=True)
    
    scenarios_meta = [
        ('Scenario A (Cold Start)', 'scenario_a_cold_run'),
        ('Scenario A (Warmed-up)', 'scenario_a_warmed_run'),
        ('Scenario B (L2 Cold, Warmed-up)', 'scenario_b_warmed_run'),
        ('Scenario C (500 VU Stress)', 'scenario_c_stress_run'),
    ]
    
    report = {}
    
    for display_name, file_prefix in scenarios_meta:
        runs_data = []
        for n in range(1, 6):
            filename = f'{file_prefix}{n}.json'
            with open(filename, 'r', encoding='utf-8') as f:
                data = json.load(f)
                m = data['metrics']
                dur = m['http_req_duration']
                reqs = m['http_reqs']
                rate200 = m.get('rate_200_ok', {'value': 1.0})
                failed = m.get('http_req_failed', {'passes': 0})
                
                runs_data.append({
                    'run': n,
                    'min': dur['min'],
                    'avg': dur['avg'],
                    'med': dur['med'],
                    'p90': dur['p(90)'],
                    'p95': dur['p(95)'],
                    'p99': dur['p(99)'],
                    'max': dur['max'],
                    'count': reqs['count'],
                    'rate': reqs['rate'],
                    'success_rate': rate200['value'] * 100.0,
                    'failed_count': failed.get('passes', 0)
                })
                
        def calc_stat(key):
            vals = [r[key] for r in runs_data]
            mean = statistics.mean(vals)
            stdev = statistics.stdev(vals) if len(vals) > 1 else 0.0
            return mean, stdev, vals

        max_vals = [r['max'] for r in runs_data]
        max_range_str = f"{min(max_vals):.2f} ms ~ {max(max_vals):.2f} ms"
        
        report[display_name] = {
            'runs': runs_data,
            'min': calc_stat('min'),
            'avg': calc_stat('avg'),
            'med': calc_stat('med'),
            'p90': calc_stat('p90'),
            'p95': calc_stat('p95'),
            'p99': calc_stat('p99'),
            'max_range': max_range_str,
            'count': calc_stat('count'),
            'rate': calc_stat('rate'),
            'success_rate': calc_stat('success_rate'),
            'failed_count': sum(r['failed_count'] for r in runs_data)
        }
        
    with open('benchmark_final_table.json', 'w', encoding='utf-8') as f:
        json.dump(report, f, indent=2)
        
    print('\n### FINAL AGGREGATED BENCHMARK TABLE (5 RUNS)\n')
    header = '| 측정 지표 | 시나리오 A (Cold Start) | 시나리오 A (Warmed-up) | 시나리오 B (L2 Cold, Warmed-up) | 시나리오 C (500 VU Stress) |'
    sep = '| :--- | :---: | :---: | :---: | :---: |'
    
    col_keys = [
        'Scenario A (Cold Start)',
        'Scenario A (Warmed-up)',
        'Scenario B (L2 Cold, Warmed-up)',
        'Scenario C (500 VU Stress)'
    ]
    
    def fmt_cell(val_mean, val_std, unit='', is_int=False):
        if is_int:
            return f"{val_mean:,.0f} ± {val_std:,.0f} {unit}".strip()
        return f"{val_mean:.2f} ± {val_std:.2f} {unit}".strip()
        
    rows = [
        ('총 완료 요청 수', lambda c: fmt_cell(report[c]['count'][0], report[c]['count'][1], '건', is_int=True)),
        ('초당 처리량 (RPS)', lambda c: fmt_cell(report[c]['rate'][0], report[c]['rate'][1], 'req/s')),
        ('성공률 (200 OK)', lambda c: f"{report[c]['success_rate'][0]:.2f}% ± {report[c]['success_rate'][1]:.2f}%"),
        ('실패 건수', lambda c: f"{report[c]['failed_count']} 건"),
        ('최소 지연 (Min)', lambda c: fmt_cell(report[c]['min'][0], report[c]['min'][1], 'ms')),
        ('평균 지연 (Avg)', lambda c: fmt_cell(report[c]['avg'][0], report[c]['avg'][1], 'ms')),
        ('중위 지연 (P50 / Med)', lambda c: fmt_cell(report[c]['med'][0], report[c]['med'][1], 'ms')),
        ('상위 90% 지연 (P90)', lambda c: fmt_cell(report[c]['p90'][0], report[c]['p90'][1], 'ms')),
        ('상위 95% 지연 (P95)', lambda c: fmt_cell(report[c]['p95'][0], report[c]['p95'][1], 'ms')),
        ('상위 99% 지연 (P99)', lambda c: fmt_cell(report[c]['p99'][0], report[c]['p99'][1], 'ms')),
        ('최대 지연 (Max 범위)', lambda c: report[c]['max_range']),
    ]
    
    md_table = [header, sep]
    for label, row_fn in rows:
        row_str = f'| {label} | ' + ' | '.join(row_fn(c) for c in col_keys) + ' |'
        md_table.append(row_str)
        
    table_content = '\n'.join(md_table)
    print(table_content)
    
    with open('benchmark_final_table.md', 'w', encoding='utf-8') as f:
        f.write(table_content)
        
    print('\nDone! Saved benchmark_final_table.json and benchmark_final_table.md')

if __name__ == '__main__':
    for c in range(1, 6):
        run_benchmark_cycle(c)
    aggregate_results()
