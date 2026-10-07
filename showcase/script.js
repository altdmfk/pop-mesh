document.addEventListener('DOMContentLoaded', () => {
  // --- Language Toggle Logic ---
  const translations = {
    en: {
      subtitle: 'High-Throughput Zero-Trust Architecture',
      tab1: 'Zero-Trust Ticketing',
      tab2: 'FIDO2 PoP Auth',
      tab3: 'PoP-Mesh',
      bp_tag_1: 'Production Demo',
      bp_title_1: 'Zero-Trust Ticketing',
      bp_sub_1: 'End-to-end operational flow',
      bp_conn_1: '→',
      bp_tag_2: 'Ingress Security',
      bp_title_2: 'FIDO2 PoP Auth',
      bp_sub_2: 'Fast-fail edge authorization',
      bp_tag_3: 'Concurrency Engine',
      bp_title_3: 'PoP-Mesh',
      bp_sub_3: 'Single-flight & in-process cache',
      bp_caption: 'A unified zero-trust edge gateway integrating hardware-bound identity verification with non-blocking concurrency control for microservice architectures.',
      card_a: 'Without Single-Flight (Baseline)',
      card_b: 'With PoP-Mesh (Active)',
      t2_title: 'FIDO2 PoP Authentication Architecture',
      t2_badge: 'Through performance optimization, latency overhead was suppressed to 25.65%, demonstrating a throughput of 780 RPS.',
      t2_desc: 'Fast-Fail Verification: Non-blocking reactive chains verify ECDSA P-256 signatures and cryptographically unique nonces, achieving O(1) request dropping in ~0.3ms.',
      btn_single: 'Send Single Request',
      btn_burst: 'Simulate 500 Burst Spike',
      btn_reset: 'Reset Metrics',
      btn_paper: 'Read Paper',
      t3_caption: 'This system trace shows PoP-Mesh handling 500 concurrent requests. Only 1 request goes to the upstream DB, while the other 499 In-Flight requests coalesce into the existing stream and instantly receive the cached response with Zero I/O overhead.',
      trace_idle: 'Waiting for incoming requests...',
      ctx_tab2: 'FIDO2 hardware private key isolation is integrated into the reverse proxy gateway. The proposed PoP authentication architecture prevents session hijacking even in token theft or memory compromise scenarios.',
      ctx_tab3: 'The zero-trust API gateway pop-mesh completes authorization entirely within the gateway process, eliminating external authorization servers. It cryptographically binds mTLS SPIFFE identities with user JWTs and prevents cache stampedes using an L1(Caffeine)/L2(Redis) tiered cache combined with a Single-Flight pattern.',
      t1_caption: 'Demonstrating how edge hardware-bound authentication and reactive gateway concurrency combine to handle high-traffic reservation bursts without upstream bottlenecks.',
      t1_legit_header: 'Legitimate Client',
      t1_sys_title: 'System: Dual-Engine Zero-Trust Gateway',
      t1_sys_badge1: 'Ingress Security: FIDO2 PoP Fast-Fail',
      t1_sys_badge2: 'Concurrency Core: PoP-Mesh (Reactive Single-Flight & L1 Cache)',
      t1_res_title: 'Ticket Reservation',
      t1_res_desc: 'Select an available seat.',
      t1_btn_reserve: 'Reserve Ticket',
      t1_callout: 'This ticketing interface acts as a live client testing the integrated pipeline: The ingress layer validates client hardware identity to block stolen-token macros at the edge, while PoP-Mesh coalesces high-concurrency seat reservation bursts to protect upstream databases from cache stampedes.',
      t1_attack_header: 'Attack Simulation & Server Logs',
      t1_macro_title: 'Macro Replay Attack',
      t1_macro_desc: 'Attacker hoards tickets at abnormal speeds using a stolen but valid JWT.',
      t1_btn_macro: 'Execute Macro',
      t1_tamper_title: 'Payload Tampering',
      t1_tamper_desc: 'Attacker alters the Seat ID in a stolen JWT and transmits it.',
      t1_btn_tamper: 'Inject Tampered Payload',
      t2_title: 'FIDO2 Hardware-Bound Proof-of-Possession',
      t2_desc: 'Fast-Fail Verification: O(1) sliding window, streaming SHA-256 for rapid rejection.',
      t3_title: 'PoP-Mesh',
      t3_badge: '500 concurrent ingress requests coalesced into 1 upstream query (99.8% downstream I/O reduction)',
      t3_desc: 'Across five independent repetitions, the L1 cache hit path consistently showed lower latency than the L2 Redis path (P50 5.55ms vs 7.02ms). After warm-up, tail latency converged significantly, and 100% availability was repeatedly confirmed even under 500 VU stress.',
      t3_db: 'Upstream DB I/O',
      t3_pool: 'Connection Pool Usage',
      t3_lat: 'P99 Tail Latency',
      t3_legend1: 'Leader',
      t3_legend2: 'In-Flight',
      t3_legend3: 'Resolved',
      t1_stage: 'STAGE',
      t1_leg_vip: 'VIP',
      t1_leg_reg: 'Regular',
      t1_leg_taken: 'Taken',
      t1_leg_sel: 'Selected',
      t1_badge_macro: '🚫 Macro Replay Blocked: FIDO2 PoP Nonce Mismatch',
      t1_badge_tamper: '⚠️ Signature Mismatch: ECDSA Verification Failed'
    },
    ko: {
      subtitle: '대규모 분산 제로트러스트 게이트웨이 아키텍처',
      tab1: '제로트러스트 티켓팅',
      tab2: 'FIDO2 PoP 인증',
      tab3: 'PoP-Mesh',
      bp_tag_1: '통합 데모',
      bp_title_1: '제로트러스트 티켓팅',
      bp_sub_1: '실시간 통합 예매 시나리오',
      bp_conn_1: '→',
      bp_tag_2: '인그레스 보안',
      bp_title_2: 'FIDO2 PoP 인증',
      bp_sub_2: '엣지 고속 인가 및 위변조 방어',
      bp_conn_2: '→',
      bp_tag_3: '동시성 엔진',
      bp_title_3: 'PoP-Mesh',
      bp_sub_3: '단일 비행 & 인프로세스 캐시',
      bp_caption: '하드웨어 기반 신원 검증과 논블로킹 동시성 제어를 통합하여 마이크로서비스 백엔드를 보호하는 제로트러스트 엣지 게이트웨이 아키텍처입니다.',
      card_a: '일반 게이트웨이 (미적용)',
      card_b: 'PoP-Mesh 적용 (동시성 제어)',
      btn_single: '단일 요청',
      btn_burst: '500건 동시 버스트 시뮬레이션',
      btn_reset: '초기화',
      btn_paper: '논문 보기',
      t3_caption: '이 시스템 트레이스는 PoP-Mesh가 500개의 동시 요청을 처리하는 과정을 보여줍니다. 단 1개의 요청만 백엔드 DB로 전달되며, 나머지 499개 요청은 In-Flight 상태에서 기존 스트림에 병합되어 대기하다 DB 부하 없이(Zero I/O) 캐시된 응답을 즉시 반환받습니다.',
      trace_idle: '인그레스 요청 대기 중...',
      ctx_tab2: '하드웨어에 개인키를 격리하는 FIDO2와 역방향 프록시 게이트웨이를 결합했습니다. 토큰 탈취나 메모리 침해 상황에서도 세션 하이재킹을 막는 PoP 인증 아키텍처를 제안합니다.',
      ctx_tab3: '제로 트러스트 API 게이트웨이 pop-mesh는 외부 인가 서버 없이 게이트웨이 내부에서 인가를 완결합니다. mTLS SPIFFE 신원과 JWT를 결합하고, L1(Caffeine)/L2(Redis) 계층형 캐시와 단일 비행 패턴으로 캐시 스탬피드를 방지합니다.',
      t1_caption: '엣지 하드웨어 바인딩 인증과 게이트웨이 동시성 제어가 결합하여, 백엔드 병목 없이 대규모 예매 트래픽을 처리하는 아키텍처를 시연합니다.',
      t1_legit_header: '정상 클라이언트',
      t1_sys_title: '시스템: 듀얼 엔진 제로트러스트 게이트웨이',
      t1_sys_badge1: '인그레스 보안: FIDO2 PoP 고속 검증',
      t1_sys_badge2: '동시성 코어: PoP-Mesh (리액티브 Single-Flight & L1 캐시)',
      t1_res_title: '티켓 예매',
      t1_res_desc: '원하시는 좌석을 선택하세요.',
      t1_btn_reserve: '예매하기',
      t1_callout: '이 인터페이스는 전체 파이프라인을 테스트하는 라이브 클라이언트입니다. 인그레스 계층은 클라이언트의 하드웨어 신원을 검증하여 탈취된 토큰의 매크로를 엣지에서 즉시 차단하고, PoP-Mesh는 대규모 예매 동시성 폭주를 병합하여 백엔드 DB를 보호합니다.',
      t1_attack_header: '공격 시뮬레이션 & 서버 로그',
      t1_macro_title: '매크로 리플레이 공격',
      t1_macro_desc: '공격자가 유효한 JWT를 탈취하여 비정상적인 속도로 매크로를 돌려 표를 싹쓸이합니다.',
      t1_btn_macro: '매크로 실행',
      t1_tamper_title: '페이로드 위변조',
      t1_tamper_desc: '공격자가 탈취한 JWT의 좌석 ID를 다른 좌석으로 조작하여 전송합니다.',
      t1_btn_tamper: '조작 페이로드 주입',
      t2_title: 'FIDO2 하드웨어 바인딩 인증 (Proof-of-Possession)',
      t2_badge: '성능 최적화를 통해 지연시간 오버헤드는 25.65%로 억제했고, 초당 780건의 처리량을 실증했습니다.',
      t2_desc: '고속 차단 (Fast-Fail): 논블로킹 리액티브 체인(Spring WebFlux)을 통해 ECDSA P-256 서명과 암호학적 논스(Nonce)를 검증하고 악성 요청을 0.3ms 이내에 O(1) 시간 복잡도로 즉시 파기합니다.',
      t3_title: 'PoP-Mesh',
      t3_badge: '500개의 동시 인그레스 요청을 1개의 업스트림 쿼리로 병합 (다운스트림 I/O 99.8% 절감)',
      t3_desc: '시나리오별 5회 독립 반복 측정 결과, L1 캐시 적중이 L2 조회보다 지연이 일관되게 낮았습니다(P50 5.55ms vs 7.02ms). 웜업 후에는 꼬리 지연이 크게 수렴했으며, 500VU 부하에서도 반복적으로 무손실 처리를 확인했습니다.',
      t3_db: '업스트림 DB I/O',
      t3_pool: '커넥션 풀 사용률',
      t3_lat: 'P99 꼬리 지연시간',
      t3_legend1: '리더 실행',
      t3_legend2: '스트림 병합',
      t3_legend3: '응답 완료',
      t1_leg_vip: 'VIP석',
      t1_leg_reg: '일반석',
      t1_leg_sel: '선택됨',
      t1_leg_taken: '예매불가'
    }
  };

  let currentLang = 'en';
  const btnKo = document.getElementById('lang-ko');
  const btnEn = document.getElementById('lang-en');

  function updateLanguage(lang) {
    currentLang = lang;
    if (btnKo) btnKo.classList.toggle('active', lang === 'ko');
    if (btnEn) btnEn.classList.toggle('active', lang === 'en');
    
    document.querySelectorAll('[data-i18n]').forEach(el => {
      const key = el.getAttribute('data-i18n');
      if (translations[lang][key]) {
        el.textContent = translations[lang][key];
      }
    });

    const terminals = document.querySelectorAll('.sf-log-line.idle');
    terminals.forEach(term => {
      term.textContent = translations[lang].trace_idle;
    });
  }

  if (btnKo) btnKo.addEventListener('click', () => updateLanguage('ko'));
  if (btnEn) btnEn.addEventListener('click', () => updateLanguage('en'));
  
  // Initialize default language
  updateLanguage('en');

  // --- Tab Switching Logic ---
  const tabBtns = document.querySelectorAll('.tab-btn');
  const tabPanes = document.querySelectorAll('.tab-pane');

  tabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      tabBtns.forEach(b => b.classList.remove('active'));
      tabPanes.forEach(p => p.classList.remove('active'));

      btn.classList.add('active');
      const targetId = btn.dataset.tab;
      document.getElementById(targetId).classList.add('active');
    });
  });

  // --- Seat Selection Logic ---
  const seatGrid = document.querySelector('.seat-grid');
  const checkoutBtn = document.getElementById('checkout-btn');
  let selectedSeatNode = null;
  let selectedSeatLabel = null;

  const rows = ['A', 'B', 'C', 'D', 'E'];
  const cols = 8;
  const seatElements = [];

  rows.forEach((rowName, rIdx) => {
    const label = document.createElement('div');
    label.className = 'seat-row-label';
    label.textContent = rowName;
    seatGrid.appendChild(label);

    const type = (rIdx < 2) ? 'vip' : 'reg';

    for(let c = 1; c <= cols; c++) {
      const seat = document.createElement('div');
      const seatLabel = `${rowName}-${c}`;
      seat.className = `seat ${type}`;
      seat.title = `Seat ${seatLabel} ${type === 'vip' ? '(VIP)' : ''}`;
      
      if (Math.random() < 0.2) {
        seat.classList.add('taken');
      } else {
        seat.addEventListener('click', () => {
          if(seat.classList.contains('taken') || seat.classList.contains('confirmed')) return;
          seatElements.forEach(s => s.classList.remove('selected'));
          seat.classList.add('selected');
          selectedSeatNode = seat;
          selectedSeatLabel = seatLabel;
          checkoutBtn.disabled = false;
        });
        seatElements.push(seat);
      }
      seatGrid.appendChild(seat);
    }
  });

  // --- Terminal Logging Logic ---
  const terminalLog = document.getElementById('terminal-log');

  const pad = (num, len = 2) => num.toString().padStart(len, '0');
  const getTimestamp = () => {
    const now = new Date();
    return `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}.${pad(now.getMilliseconds(), 3)}`;
  };

  function logToTerminal(message, type = 'info') {
    const idle = terminalLog.querySelector('.idle');
    if (idle) idle.remove();
    const entry = document.createElement('div');
    entry.className = `log-entry ${type}`;
    entry.innerHTML = `<span class="time">[${getTimestamp()}]</span> ${message}`;
    terminalLog.appendChild(entry);
    terminalLog.scrollTop = terminalLog.scrollHeight;
  }

  // --- Simulation Control Logic ---
  let checkoutTimeout = null;

  // 1. Normal Booking (FIDO2 Bound)
  checkoutBtn.addEventListener('click', () => {
    if (!selectedSeatLabel) return;
    
    logToTerminal(`[Client] Initiating checkout for seat ${selectedSeatLabel}...`, 'info');
    checkoutBtn.disabled = true;
    
    if (checkoutTimeout) clearTimeout(checkoutTimeout);
    
    checkoutTimeout = setTimeout(() => {
      logToTerminal('[ContextExtractionFilter] ECDSA Signature and Nonce freshness verified.', 'info');
      logToTerminal('[PoP-Mesh] Single-Flight Policy Engine: No concurrent burst detected. Routing to upstream DB.', 'trace');
      logToTerminal(`[PoP-Mesh] 200 OK - Seat ${selectedSeatLabel} successfully reserved.`, 'success');
      
      if (selectedSeatNode) {
        selectedSeatNode.className = 'seat taken';
      }
      
      selectedSeatLabel = null;
      selectedSeatNode = null;
      checkoutBtn.disabled = true;
    }, 800);
  });

  // 2. Macro Replay Attack Simulation
  document.getElementById('btn-simulate-macro').addEventListener('click', () => {
    logToTerminal('[Attacker] Replaying stolen JWT at high speed to hoard tickets (50 req/sec)...', 'warning');
    
    setTimeout(() => logToTerminal('[Netty Ingress] POST /api/v1/tickets/reserve (x12 received)', 'trace'), 50);
    setTimeout(() => logToTerminal('[Netty Ingress] POST /api/v1/tickets/reserve (x24 received)', 'trace'), 150);
    setTimeout(() => logToTerminal('[Netty Ingress] POST /api/v1/tickets/reserve (x14 received)', 'trace'), 250);
    
    const overlay = document.getElementById('attack-overlay-macro');
    const targets = document.querySelectorAll('.seat:not(.taken)');
    
    overlay.className = 'attack-overlay';
    targets.forEach(s => s.classList.add('attack-flash'));
    
    setTimeout(() => {
      overlay.className = 'attack-overlay hidden';
      targets.forEach(s => s.classList.remove('attack-flash'));
      logToTerminal('[ContextExtractionFilter] DROP: Reused FIDO2 Nonce detected in hardware-bound JWT.', 'error');
      logToTerminal('[ContextExtractionFilter] 50 concurrent requests rejected in O(1) time (avg 0.38ms). Zero downstream I/O.', 'success');
      logToTerminal('[PoP-Mesh] 0 requests reached the mesh core (Isolated at Edge). Backend protected.', 'info');
    }, 600);
  });

  // 3. Payload Tampering Simulation
  document.getElementById('btn-simulate-tamper').addEventListener('click', () => {
    logToTerminal('[Attacker] Intercepting valid JWT and modifying the Seat ID payload to a VIP seat...', 'warning');
    
    const overlay = document.getElementById('attack-overlay-macro');
    const target = document.querySelector('.seat.vip:not(.taken)');
    
    overlay.className = 'attack-overlay';
    if (target) target.classList.add('tamper-flash');
    
    setTimeout(() => {
      if (target) target.classList.remove('tamper-flash');
      overlay.className = 'attack-overlay hidden';
      logToTerminal('[ContextExtractionFilter] VERIFY FAILED: JWT payload altered without valid private key.', 'error');
      logToTerminal('[ContextExtractionFilter] Request context aborted. ECDSA P-256 signature verification failed. Returning 401 Unauthorized.', 'error');
      logToTerminal('[PoP-Mesh] 0 requests reached the mesh core (Isolated at Edge). Backend protected.', 'info');
    }, 600);
  });

  // --- Single-Flight Dashboard Logic (Metrics & Waterfall) ---
  const btnSingle = document.getElementById('sf-btn-single');
  const btnBurst = document.getElementById('sf-btn-burst');
  const btnReset = document.getElementById('sf-btn-reset');
  
  const mDbOff = document.getElementById('metric-db-off');
  const mPoolOff = document.getElementById('metric-pool-off');
  const mLatOff = document.getElementById('metric-lat-off');
  const barPoolOff = document.getElementById('bar-pool-off');
  
  const mDbOn = document.getElementById('metric-db-on');
  const mPoolOn = document.getElementById('metric-pool-on');
  const mLatOn = document.getElementById('metric-lat-on');
  const barPoolOn = document.getElementById('bar-pool-on');

  const wfDynamicRows = document.getElementById('wf-dynamic-rows');
  const fanoutBadge = document.getElementById('fanout-badge');

  let sfTimeouts = [];
  
  function clearSfTimeouts() {
    sfTimeouts.forEach(clearTimeout);
    sfTimeouts = [];
  }

  const wfData = [];
  for (let i = 1; i <= 3; i++) {
    wfData.push({
      id: i,
      label: i === 1 ? 'Req #1 [Leader]' : `Req #${i}`,
      type: i === 1 ? 'leader' : 'coalesced'
    });
  }
  wfData.push({
    id: 'ellipsis',
    label: '...',
    type: 'coalesced',
    isEllipsis: true
  });
  wfData.push({
    id: 500,
    label: 'Req #500',
    type: 'coalesced'
  });

  function renderWaterfall(isBurst) {
    wfDynamicRows.innerHTML = '';
    const rowsToRender = isBurst ? wfData : [wfData[0]];
    
    rowsToRender.forEach(d => {
      const row = document.createElement('div');
      row.className = 'wf-row';
      row.id = `wf-row-${d.id}`;
      if (d.isEllipsis) {
        row.innerHTML = `
          <div class="wf-label" style="text-align: center; color: #64748b; font-weight: bold;">...</div>
          <div class="wf-bar-container"></div>
        `;
      } else {
        row.innerHTML = `
          <div class="wf-label">${d.label}</div>
          <div class="wf-bar-container">
            <div class="wf-bar" id="wf-bar-${d.id}"></div>
            <span class="wf-bar-text" id="wf-text-${d.id}"></span>
          </div>
        `;
      }
      wfDynamicRows.appendChild(row);
    });
  }

  const termBody = document.getElementById('sf-term-body');

  function clearTerminal() {
    if(termBody) {
      termBody.innerHTML = `<div class="sf-log-line idle">${translations[currentLang].trace_idle}</div>`;
    }
  }

  function appendLog(level, message, delay = 0) {
    if (!termBody) return;
    if (delay === 0) {
      const line = document.createElement('div');
      line.className = `sf-log-line ${level.toLowerCase()}`;
      line.innerHTML = `<span class="time">[${getTimestamp()}]</span> <span class="prefix">[${level.toUpperCase()}]</span> ${message}`;
      termBody.appendChild(line);
      termBody.scrollTop = termBody.scrollHeight;
    } else {
      sfTimeouts.push(setTimeout(() => appendLog(level, message, 0), delay));
    }
  }

  function resetDashboard() {
    clearSfTimeouts();
    mDbOff.textContent = '0';
    mPoolOff.textContent = '0%';
    mLatOff.textContent = '0';
    barPoolOff.style.width = '0%';
    
    mDbOn.textContent = '0';
    mPoolOn.textContent = '0.0%';
    mLatOn.textContent = '0';
    barPoolOn.style.width = '0%';
    
    mDbOff.classList.remove('text-danger');
    mPoolOff.classList.remove('text-danger');
    mLatOff.classList.remove('text-danger');
    barPoolOff.classList.remove('danger');
    barPoolOff.classList.add('danger');
    
    wfDynamicRows.innerHTML = '';
    
    const scrubber = document.getElementById('wf-scrubber');
    if(scrubber) {
      scrubber.style.transition = 'none';
      scrubber.style.opacity = '0';
      scrubber.style.transform = 'translateX(0%)';
    }
    
    if(fanoutBadge) fanoutBadge.style.opacity = '0';
    const savedBadge = document.getElementById('saved-badge');
    if (savedBadge) savedBadge.style.opacity = '0';
    
    btnSingle.disabled = false;
    btnBurst.disabled = false;
    
    clearTerminal();
  }

  function animateCount(elem, endVal, duration, suffix = '') {
    const steps = 20;
    const stepTime = duration / steps;
    const inc = endVal / steps;
    let curr = 0;
    
    elem.textContent = '0' + suffix;
    const timer = setInterval(() => {
      curr += inc;
      if (curr >= endVal) {
        curr = endVal;
        clearInterval(timer);
      }
      elem.textContent = (Number.isInteger(endVal) ? Math.floor(curr) : curr.toFixed(1)) + suffix;
    }, stepTime);
    sfTimeouts.push(timer);
  }

  function runSimulation(isBurst) {
    clearSfTimeouts();
    btnSingle.disabled = true;
    btnBurst.disabled = true;
    
    renderWaterfall(isBurst); 
    
    if (termBody) {
      const idleLine = termBody.querySelector('.idle');
      if (idleLine) idleLine.remove();
    }
    
    if (fanoutBadge) fanoutBadge.style.opacity = '0';
    const savedBadge = document.getElementById('saved-badge');
    if (savedBadge) savedBadge.style.opacity = '0';
    
    mDbOff.classList.remove('text-danger');
    mPoolOff.classList.remove('text-danger');
    mLatOff.classList.remove('text-danger');

    const scrubber = document.getElementById('wf-scrubber');
    scrubber.style.transition = 'none';
    scrubber.style.opacity = '0';
    scrubber.style.transform = 'translateX(0%)';

    const duration = 600; // Snappy 0.6s total

    // Phase 1: Instant Ingress
    if (isBurst) {
      animateCount(mDbOff, 500, duration);
      animateCount(mLatOff, 526, duration);
      
      sfTimeouts.push(setTimeout(() => {
        mDbOff.classList.add('text-danger');
        mPoolOff.classList.add('text-danger');
        mLatOff.classList.add('text-danger');
      }, duration));
      
      barPoolOff.style.transition = `width ${duration}ms ease-out`;
      barPoolOff.style.width = '100%';
      animateCount(mPoolOff, 100, duration, '%');

      // Instant cascade (all at exactly 0ms baseline simultaneously)
      wfData.forEach((d) => {
        const row = document.getElementById(`wf-row-${d.id}`);
        if (row) row.classList.add('show');
        
        if (d.type === 'coalesced') {
          const bar = document.getElementById(`wf-bar-${d.id}`);
          const text = document.getElementById(`wf-text-${d.id}`);
          if (bar && text) {
            bar.className = 'wf-bar wait active';
            bar.style.width = '15px'; // frozen short
            text.textContent = 'Waiting...';
          }
        }
      });
      
      appendLog('trace', '[Ingress] Concurrent burst detected on URI: /api/v1/ticket');
        for (let i = 1; i <= 6; i++) {
          appendLog('trace', `[Ingress] Routing Request #${i} to authentication filter...`, i * 5);
        }
        appendLog('trace', '[Ingress] ... and 494 more requests arrived simultaneously (500 VU Load).', 35);
        appendLog('trace', '[PoP-Mesh] Cache Miss on L1(Caffeine) and L2(Redis).', 40);
        appendLog('trace', '[Single-Flight] Request #1 acquired mutex and is promoted as Leader.', 45);
        appendLog('trace', '[Reactive Core] Requests #2 to 500 blocked from backend to prevent Cache Stampede.', 55);
        appendLog('trace', '[Reactive Core] Coalescing 499 In-Flight requests into shared Mono.share() pipeline.', 60);

    } else {
      mDbOff.textContent = '1';
      mLatOff.textContent = '35';
      barPoolOff.style.transition = `width 0.2s linear`;
      barPoolOff.style.width = '1%';
      mPoolOff.textContent = '1%';
      
      const row = document.getElementById('wf-row-1');
      if (row) row.classList.add('show');
      
      appendLog('trace', '[Ingress] Single reservation request received. No active in-flight queue, dispatching directly to backend DB.');
    }

    mDbOn.textContent = '1';
    barPoolOn.style.width = '0.2%';
    mPoolOn.textContent = '0.2%';

    // Phase 2: Leader active
    sfTimeouts.push(setTimeout(() => {
      scrubber.style.transition = `transform ${duration}ms ease-in-out`;
      scrubber.style.opacity = '1';
      scrubber.style.transform = 'translateX(90%)';

      const leaderBar = document.getElementById('wf-bar-1');
      const leaderText = document.getElementById('wf-text-1');
      if (leaderBar && leaderText) {
        leaderBar.className = 'wf-bar leader active';
        leaderBar.style.transition = `width ${duration}ms ease-out`;
        leaderBar.style.width = '80%';
        leaderText.textContent = 'DB I/O...';
      }
    }, 20));

    // Phase 3: Resolution Snap
    sfTimeouts.push(setTimeout(() => {
      mLatOn.textContent = isBurst ? '26.9' : '35.0';
      scrubber.style.opacity = '0';
      
      const leaderBar = document.getElementById('wf-bar-1');
      const leaderText = document.getElementById('wf-text-1');
      if (leaderBar && leaderText) {
        leaderBar.className = 'wf-bar resolved active';
        leaderBar.style.transition = 'width 0.1s ease-out';
        leaderBar.style.width = '100%';
        leaderText.textContent = '200 OK';
      }
      
      if (isBurst) {
        wfData.forEach(d => {
          if (d.type === 'coalesced') {
            const bar = document.getElementById(`wf-bar-${d.id}`);
            const text = document.getElementById(`wf-text-${d.id}`);
            if (bar && text) {
              bar.className = 'wf-bar resolved active';
              bar.style.transition = 'width 0.1s ease-out';
              bar.style.width = '100%'; 
              text.textContent = 'Resolved (0 I/O)';
            }
          }
        });
        if (fanoutBadge) {
          fanoutBadge.style.opacity = '1';
        }
        if (savedBadge) savedBadge.style.opacity = '1';
        
        appendLog('success', '[Single-Flight] Leader response received from upstream DB.');
          appendLog('success', '[PoP-Mesh] Populated L1(Caffeine) and L2(Redis) caches.');
          appendLog('success', '[Fan-Out] Broadcasting payload to 499 awaiting subscribers (Zero I/O Overhead).');
          appendLog('trace', '[Metric] L1 Cache Hit P50 Latency: 5.55ms | L2 Cache Hit P50 Latency: 7.02ms');
          appendLog('trace', '[Metric] Benchmark Result: 780 RPS (+437%), latency reduced by 94%.');
          appendLog('trace', '[Metric] Processed 500 concurrent requests with 0% error rate (100% availability).');
      } else {
        appendLog('success', '[Upstream DB] Successfully fetched data from the backend DB (35ms roundtrip).');
      }
      
      btnSingle.disabled = false;
      btnBurst.disabled = false;
    }, duration + 50));
  }

  btnSingle.addEventListener('click', () => runSimulation(false));
  btnBurst.addEventListener('click', () => runSimulation(true));
  btnReset.addEventListener('click', resetDashboard);

  resetDashboard();
});

// Paper Modal Logic
document.addEventListener('DOMContentLoaded', () => {
  const btnReadPaper = document.getElementById('btn-read-paper');
  const modalOverlay = document.getElementById('paper-modal');
  const modalClose = document.getElementById('close-modal');
  const paperContent = document.getElementById('paper-content');

  if (btnReadPaper && modalOverlay && modalClose && paperContent) {
    let paperLoaded = false;

    btnReadPaper.addEventListener('click', (e) => {
      e.preventDefault();
      modalOverlay.classList.add('show');
      
      if (!paperLoaded) {
        fetch('paper.md')
          .then(res => {
            if(!res.ok) throw new Error('Network response was not ok');
            return res.text();
          })
          .then(text => {
            text = text.replace(/\.\.\/IMG\//g, 'IMG/');
            if (typeof marked !== 'undefined') {
              paperContent.innerHTML = marked.parse(text);
              if (typeof renderMathInElement !== 'undefined') {
                renderMathInElement(paperContent, {
                  delimiters: [
                    {left: '$$', right: '$$', display: true},
                    {left: '$', right: '$', display: false}
                  ],
                  throwOnError: false
                });
              }
            } else {
              paperContent.innerHTML = '<pre style="white-space: pre-wrap; font-family: inherit;">' + text + '</pre>';
            }
            paperLoaded = true;
          })
          .catch(err => {
            paperContent.innerHTML = '<span class="text-danger">Failed to load paper.md. Please ensure the server is running correctly.</span>';
            console.error(err);
          });
      }
    });

    modalClose.addEventListener('click', () => {
      modalOverlay.classList.remove('show');
    });

    modalOverlay.addEventListener('click', (e) => {
      if (e.target === modalOverlay) {
        modalOverlay.classList.remove('show');
      }
    });
  }
});

