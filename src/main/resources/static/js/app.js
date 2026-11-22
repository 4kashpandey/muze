/**
 * muze - Stage 1 Main Application Orchestrator
 * Supports 3-view lifecycle: Landing/Auth -> Active Rooms Browser -> Synchronized Room Player.
 */
document.addEventListener('DOMContentLoaded', () => {
    // --- Application State with Persistent Identity ---
    const savedUserId = localStorage.getItem('muze_user_id') || ('usr_' + Math.random().toString(36).substring(2, 9));
    localStorage.setItem('muze_user_id', savedUserId);

    let currentUser = {
        userId: savedUserId,
        username: localStorage.getItem('muze_username') || 'Alex',
        email: null,
        role: 'LISTENER',
        isGuest: true,
        token: localStorage.getItem('muze_token') || null,
        roomId: null
    };

    let wsClient = null;
    let clockSync = null;
    let driftEngine = null;
    let localTracks = [];
    let isScrubbing = false;
    let searchDebounceTimer = null;
    let pendingPrivateRoomId = null;

    // Muze Vinyl Record Artwork Placeholder
    const DEFAULT_VINYL_ARTWORK = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='120' height='120' viewBox='0 0 24 24'><circle cx='12' cy='12' r='11' fill='%231B1F1D'/><circle cx='12' cy='12' r='7.5' fill='none' stroke='%232A302C' stroke-width='0.75'/><circle cx='12' cy='12' r='4' fill='%23D8B0C7'/><circle cx='12' cy='12' r='1.2' fill='%230F1110'/></svg>";

    // --- DOM Elements: Views ---
    const viewLanding = document.getElementById('view-landing');
    const loungeContainer = document.getElementById('lounge-container');
    const viewRooms = document.getElementById('view-rooms');
    const viewPlayer = document.getElementById('view-player');

    // --- DOM Elements: Landing / Auth ---
    const tabLogin = document.getElementById('tab-login');
    const tabSignup = document.getElementById('tab-signup');
    const tabGuest = document.getElementById('tab-guest');
    const btnGoogleAuth = document.getElementById('btn-google-auth');
    const authUsernameInput = document.getElementById('auth-username');
    const authUsernameLabel = document.getElementById('auth-username-label');
    const authEmailInput = document.getElementById('auth-email');
    const emailGroup = document.getElementById('email-group');
    const authPasswordInput = document.getElementById('auth-password');
    const passwordGroup = document.getElementById('password-group');
    const authErrorMsg = document.getElementById('auth-error-msg');
    const btnEnterApp = document.getElementById('btn-enter-app');
    const btnEnterText = document.getElementById('btn-enter-text');
    const guestHint = document.getElementById('guest-hint');

    // --- DOM Elements: Active Rooms Browser ---
    const greetingUsername = document.getElementById('greeting-username');
    const greetingBadge = document.getElementById('greeting-badge');
    const userAvatar = document.getElementById('user-avatar');
    const activeRoomsGrid = document.getElementById('active-rooms-grid');
    const activeRoomsCount = document.getElementById('active-rooms-count');
    const btnRefreshRooms = document.getElementById('btn-refresh-rooms');
    const btnOpenCreateModal = document.getElementById('btn-open-create-modal');
    const btnLogout = document.getElementById('btn-logout');
    const directRoomCodeInput = document.getElementById('direct-room-code');
    const btnDirectJoin = document.getElementById('btn-direct-join');

    // --- DOM Elements: Modals ---
    const modalCreateRoom = document.getElementById('modal-create-room');
    const btnCloseCreateModal = document.getElementById('btn-close-create-modal');
    const btnCancelCreate = document.getElementById('btn-cancel-create');
    const btnConfirmCreate = document.getElementById('btn-confirm-create');
    const modalRoomName = document.getElementById('modal-room-name');
    const passcodeInputWrap = document.getElementById('passcode-input-wrap');
    const modalRoomPasscode = document.getElementById('modal-room-passcode');
    const visibilityRadios = document.querySelectorAll('input[name="room-visibility"]');

    const modalPasscodePrompt = document.getElementById('modal-passcode-prompt');
    const btnClosePasscodeModal = document.getElementById('btn-close-passcode-modal');
    const btnCancelPasscode = document.getElementById('btn-cancel-passcode');
    const btnSubmitPasscode = document.getElementById('btn-submit-passcode');
    const inputPromptPasscode = document.getElementById('input-prompt-passcode');
    const passcodeErrorMsg = document.getElementById('passcode-error-msg');

    // --- DOM Elements: Player View ---
    const btnLeaveRoom = document.getElementById('btn-leave-room');
    const displayRoomId = document.getElementById('display-room-id');
    const displayRoomType = document.getElementById('display-room-type');
    const displayUserRole = document.getElementById('display-user-role');
    const btnCopyLink = document.getElementById('btn-copy-link');
    const trackArtwork = document.getElementById('track-artwork');
    const trackTitle = document.getElementById('track-title');
    const trackArtist = document.getElementById('track-artist');
    const trackGenre = document.getElementById('track-genre');
    const btnPlayPause = document.getElementById('btn-play-pause');
    const playIcon = document.getElementById('play-icon');
    const pauseIcon = document.getElementById('pause-icon');
    const progressBar = document.getElementById('progress-bar');
    const progressFill = document.getElementById('progress-fill');
    const timeCurrent = document.getElementById('time-current');
    const timeTotal = document.getElementById('time-total');
    const volumeSlider = document.getElementById('volume-slider');
    const hostHintText = document.getElementById('host-hint-text');
    const participantsList = document.getElementById('participants-list');
    const participantCountBadge = document.getElementById('participant-count-badge');

    // --- DOM Elements: Song Queue ---
    const btnSkipTrack = document.getElementById('btn-skip-track');
    const queueCountBadge = document.getElementById('queue-count-badge');
    const queueEmptyMsg = document.getElementById('queue-empty-msg');
    const queueList = document.getElementById('queue-list');
    let currentQueue = [];

    // --- DOM Elements: HUD & Notices ---
    const hudRtt = document.getElementById('hud-rtt');
    const hudOffset = document.getElementById('hud-offset');
    const hudDelta = document.getElementById('hud-delta');
    const hudStatus = document.getElementById('hud-status');
    const hudCorrections = document.getElementById('hud-corrections');
    const autoplayNotice = document.getElementById('autoplay-notice');
    const btnUnmute = document.getElementById('btn-unmute');

    // --- DOM Elements: Music Catalog ---
    const musicSearchInput = document.getElementById('music-search-input');
    const btnClearSearch = document.getElementById('btn-clear-search');
    const catalogResults = document.getElementById('catalog-results');

    // --- Initialization ---
    loadLocalCatalog();
    checkExistingAuth();

    // Check direct invite URL parameter: ?room=XXXXXX
    const urlParams = new URLSearchParams(window.location.search);
    const roomFromUrl = urlParams.get('room');
    if (roomFromUrl) {
        directRoomCodeInput.value = roomFromUrl.toUpperCase();
    }

    // --- View Navigation System ---
    function switchView(viewName) {
        if (viewLanding) viewLanding.classList.add('hidden');
        if (loungeContainer) loungeContainer.classList.add('hidden');
        if (viewRooms) viewRooms.classList.add('hidden');
        if (viewPlayer) viewPlayer.classList.add('hidden');

        if (viewName === 'landing') {
            if (viewLanding) viewLanding.classList.remove('hidden');
        } else if (viewName === 'rooms') {
            if (loungeContainer) loungeContainer.classList.remove('hidden');
            if (viewRooms) viewRooms.classList.remove('hidden');
            const cleanName = (currentUser.username || 'muzer').replace(/^@+/, '');
            greetingUsername.textContent = '@' + cleanName;
            userAvatar.textContent = cleanName.charAt(0).toUpperCase();
            if (greetingBadge) greetingBadge.textContent = (currentUser.isGuest) ? 'Guest Account' : 'Verified Member';
            fetchActiveRooms();
        } else if (viewName === 'player') {
            if (loungeContainer) loungeContainer.classList.remove('hidden');
            if (viewPlayer) viewPlayer.classList.remove('hidden');
        }
    }

    // --- VIEW 1: Auth & Landing Event Handlers ---
    // authMode drives which fields show and which endpoint is called:
    //   'signup' -> username + email + password -> POST /api/v1/auth/register
    //   'login'  -> username + password         -> POST /api/v1/auth/login
    let authMode = 'signup';

    const authModeSwitch = document.getElementById('auth-mode-switch');

    // Password visibility toggle
    const btnTogglePassword = document.getElementById('btn-toggle-password');
    const eyeIconShow = document.getElementById('eye-icon-show');
    const eyeIconHide = document.getElementById('eye-icon-hide');
    if (btnTogglePassword && authPasswordInput) {
        btnTogglePassword.addEventListener('click', (e) => {
            e.preventDefault();
            const isPass = authPasswordInput.getAttribute('type') === 'password';
            authPasswordInput.setAttribute('type', isPass ? 'text' : 'password');
            if (eyeIconShow) eyeIconShow.classList.toggle('hidden', isPass);
            if (eyeIconHide) eyeIconHide.classList.toggle('hidden', !isPass);
        });
    }

    // Tab switching
    if (tabLogin) tabLogin.addEventListener('click', () => setAuthMode('login'));
    if (tabSignup) tabSignup.addEventListener('click', () => setAuthMode('signup'));

    // Direct guest entry — reuses username if typed, else auto-names
    if (tabGuest) {
        tabGuest.addEventListener('click', () => {
            const typedName = authUsernameInput ? authUsernameInput.value.trim() : '';
            currentUser.username = typedName ? typedName : ('Guest-' + Math.floor(1000 + Math.random() * 9000));
            currentUser.isGuest = true;
            currentUser.token = null;
            currentUser.userId = 'usr_' + Math.random().toString(36).substring(2, 9);
            switchView('rooms');
        });
    }

    // Boot into signup mode (shows username + email + password)
    setAuthMode('signup');

    function setAuthMode(mode) {
        authMode = mode;
        if (tabLogin) tabLogin.classList.toggle('active', mode === 'login');
        if (tabSignup) tabSignup.classList.toggle('active', mode === 'signup');
        if (authErrorMsg) {
            authErrorMsg.classList.add('hidden');
            authErrorMsg.textContent = '';
        }

        if (mode === 'signup') {
            // All three fields visible
            if (emailGroup) emailGroup.classList.remove('hidden');
            if (passwordGroup) passwordGroup.classList.remove('hidden');
            if (btnEnterText) btnEnterText.textContent = 'Sign up';
            if (authUsernameLabel) authUsernameLabel.textContent = 'Username';
            if (authUsernameInput) authUsernameInput.placeholder = 'Choose your username';
        } else {
            // Login: username + password only
            if (emailGroup) emailGroup.classList.add('hidden');
            if (passwordGroup) passwordGroup.classList.remove('hidden');
            if (btnEnterText) btnEnterText.textContent = 'Sign in';
            if (authUsernameLabel) authUsernameLabel.textContent = 'Username or email';
            if (authUsernameInput) authUsernameInput.placeholder = 'Enter username or email';
        }
    }

    function showAuthError(msg) {
        if (authErrorMsg) {
            authErrorMsg.textContent = msg;
            authErrorMsg.classList.remove('hidden');
        } else {
            alert(msg);
        }
    }

    function onAuthSuccess(data) {
        currentUser.token = data.token;
        if (data.userId) {
            currentUser.userId = data.userId.toString();
            localStorage.setItem('muze_user_id', currentUser.userId);
        }
        currentUser.username = data.username;
        localStorage.setItem('muze_username', data.username);
        currentUser.email = data.email;
        currentUser.isGuest = false;
        localStorage.setItem('muze_token', data.token);
        switchView('rooms');
    }

    [authUsernameInput, authEmailInput, authPasswordInput].forEach(inp => {
        if (inp) {
            inp.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    btnEnterApp.click();
                }
            });
        }
    });

    btnEnterApp.addEventListener('click', async () => {
        if (authErrorMsg) authErrorMsg.classList.add('hidden');
        const username = authUsernameInput ? authUsernameInput.value.trim() : '';
        const password = authPasswordInput ? authPasswordInput.value : '';
        const email = authEmailInput ? authEmailInput.value.trim() : '';

        if (!username) {
            showAuthError('Please enter a username');
            return;
        }

        if (!password) {
            showAuthError('Please enter a password');
            return;
        }

        btnEnterApp.disabled = true;
        try {
            if (authMode === 'signup') {
                if (!email) {
                    showAuthError('Please enter a valid email address');
                    btnEnterApp.disabled = false;
                    return;
                }
                const res = await fetch('/api/v1/auth/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username, email, password })
                });
                const data = await res.json();
                if (res.ok) {
                    onAuthSuccess(data);
                } else {
                    showAuthError(data.error || 'Registration failed. Try a different username/email.');
                }
            } else {
                // login
                const res = await fetch('/api/v1/auth/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ usernameOrEmail: username, password })
                });
                const data = await res.json();
                if (res.ok) {
                    onAuthSuccess(data);
                } else {
                    showAuthError(data.error || 'Invalid credentials.');
                }
            }
        } catch (e) {
            showAuthError('Network error: ' + e.message);
        } finally {
            btnEnterApp.disabled = false;
        }
    });

    // --- Desktop Interactive Cursor Parallax & Ambient Spotlight ---
    const welcomeScreen = document.getElementById('view-landing');
    const vinylWrap = document.querySelector('.vinyl-record-wrap');

    if (welcomeScreen) {
        let mouseX = 0, mouseY = 0;
        let targetX = 0, targetY = 0;
        let isTracking = false;

        welcomeScreen.addEventListener('mousemove', (e) => {
            if (window.innerWidth <= 820) return;
            const rect = welcomeScreen.getBoundingClientRect();
            targetX = ((e.clientX - rect.left) / rect.width - 0.5) * 2;
            targetY = ((e.clientY - rect.top) / rect.height - 0.5) * 2;

            welcomeScreen.style.setProperty('--cursor-x', `${e.clientX}px`);
            welcomeScreen.style.setProperty('--cursor-y', `${e.clientY}px`);

            if (!isTracking) {
                isTracking = true;
                requestAnimationFrame(renderParallax);
            }
        });

        function renderParallax() {
            mouseX += (targetX - mouseX) * 0.08;
            mouseY += (targetY - mouseY) * 0.08;

            if (vinylWrap) {
                vinylWrap.style.transform = `translate3d(${mouseX * 20}px, ${mouseY * 20}px, 0)`;
            }

            if (Math.abs(targetX - mouseX) > 0.001 || Math.abs(targetY - mouseY) > 0.001) {
                requestAnimationFrame(renderParallax);
            } else {
                isTracking = false;
            }
        }
    }

    async function checkExistingAuth() {
        const token = localStorage.getItem('muze_token');
        if (!token) return;

        try {
            const res = await fetch('/api/v1/auth/me', {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (res.ok) {
                const me = await res.json();
                currentUser.token = token;
                currentUser.username = me.username;
                currentUser.isGuest = false;
                switchView('rooms');
            } else {
                localStorage.removeItem('muze_token');
            }
        } catch (e) {
            // Offline or server not yet ready, ignore
        }
    }

    // --- VIEW 2: Active Rooms Browser Logic ---
    let latestActiveRooms = [];
    let currentGenreFilter = 'all';

    btnRefreshRooms.addEventListener('click', fetchActiveRooms);
    btnLogout.addEventListener('click', () => {
        localStorage.removeItem('muze_token');
        currentUser.token = null;
        currentUser.isGuest = true;
        currentUser.username = 'Guest-' + Math.floor(1000 + Math.random() * 9000);
        switchView('landing');
    });

    async function fetchActiveRooms() {
        activeRoomsGrid.innerHTML = '<div class="loading-state">Refreshing live lounges...</div>';
        try {
            const res = await fetch('/api/v1/rooms/active');
            if (res.ok) {
                latestActiveRooms = await res.json();
                renderActiveRooms(latestActiveRooms);
            } else {
                activeRoomsGrid.innerHTML = '<div class="empty-state">Unable to load rooms. Please refresh.</div>';
            }
        } catch (e) {
            console.error('Failed to fetch active rooms:', e);
            activeRoomsGrid.innerHTML = '<div class="empty-state">Connection failed. Check server status.</div>';
        }
    }

    function renderActiveRooms(rooms) {
        activeRoomsGrid.innerHTML = '';
        const count = rooms ? rooms.length : 0;
        if (activeRoomsCount) activeRoomsCount.textContent = `● ${count} live`;

        if (!rooms || rooms.length === 0) {
            activeRoomsGrid.innerHTML = `
                <div class="rooms-empty-card">
                    <div class="empty-vinyl-ring" aria-hidden="true"></div>
                    <p class="empty-title">No lounges broadcasting yet</p>
                    <p class="empty-sub">Good taste deserves company. Be the first to start a session tonight.</p>
                    <button class="btn-host-session-sm" onclick="document.getElementById('btn-open-create-modal').click()">
                        Host a Muze session <span class="btn-arrow-inline">→</span>
                    </button>
                </div>
            `;
            return;
        }

        rooms.forEach(room => {
            const strip = document.createElement('div');
            strip.className = 'lounge-strip';

            const artUrl = room.currentTrackArtworkUrl || DEFAULT_VINYL_ARTWORK;
            const trackText = room.currentTrackTitle ? `${escapeHtml(room.currentTrackTitle)}` : 'No track playing';
            const artistText = room.currentTrackArtist ? `${escapeHtml(room.currentTrackArtist)}` : 'muze Studio';
            const hostClean = (room.hostUsername || 'host').replace(/^@+/, '');
            const isPublic = room.isPublic !== false;
            const visibilityClass = isPublic ? 'badge-public' : 'badge-private';
            const visibilityText = isPublic ? 'PUBLIC' : 'PRIVATE';
            const countListeners = room.participantCount || 1;

            strip.innerHTML = `
                <div class="lounge-strip-art">
                    <img src="${escapeHtml(artUrl)}" alt="Lounge Artwork" onerror="this.src='${DEFAULT_VINYL_ARTWORK}'">
                </div>

                <div class="lounge-strip-body">
                    <div class="lounge-strip-header">
                        <h3 class="lounge-strip-name">${escapeHtml(room.name)}</h3>
                        <div class="lounge-strip-badges">
                            <span class="badge-visibility ${visibilityClass}">${visibilityText}</span>
                        </div>
                    </div>

                    <div class="lounge-strip-track">${trackText}</div>
                    <div class="lounge-strip-artist">${artistText}</div>

                    <div class="lounge-strip-footer">
                        <div class="lounge-strip-meta">
                            <span class="lounge-strip-host-label">HOSTED BY</span>
                            <span class="lounge-strip-host">@${escapeHtml(hostClean)}</span>
                            <span class="meta-dot-sep">·</span>
                            <span class="lounge-strip-listeners">${countListeners} listening</span>
                        </div>
                        <button class="btn-join-strip">${isPublic ? 'Join lounge' : 'Enter code'} <span class="btn-arrow-inline">→</span></button>
                    </div>
                </div>
            `;

            // Button click handling
            const joinBtn = strip.querySelector('.btn-join-strip');
            joinBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                if (isPublic) {
                    initiateJoinRoom(room.roomId);
                } else {
                    promptPasscodeForRoom(room.roomId);
                }
            });

            // Card click also initiates join if public
            strip.addEventListener('click', () => {
                if (isPublic) {
                    initiateJoinRoom(room.roomId);
                } else {
                    promptPasscodeForRoom(room.roomId);
                }
            });

            activeRoomsGrid.appendChild(strip);
        });
    }

    // Direct Join by Code
    btnDirectJoin.addEventListener('click', () => {
        const code = directRoomCodeInput.value.trim().toUpperCase();
        if (!code) {
            alert('Please enter a room code');
            return;
        }
        initiateJoinRoom(code);
    });

    // --- Create Room Modal Logic ---
    btnOpenCreateModal.addEventListener('click', () => {
        if (currentUser.isGuest || !currentUser.token) {
            alert('Hosting a room requires a verified member account. Please Sign In or Create an Account!');
            switchView('landing');
            setAuthMode('login');
            return;
        }
        modalCreateRoom.classList.remove('hidden');
    });

    btnCloseCreateModal.addEventListener('click', () => modalCreateRoom.classList.add('hidden'));
    btnCancelCreate.addEventListener('click', () => modalCreateRoom.classList.add('hidden'));

    visibilityRadios.forEach(radio => {
        radio.addEventListener('change', (e) => {
            passcodeInputWrap.classList.toggle('hidden', e.target.value !== 'private');
        });
    });

    btnConfirmCreate.addEventListener('click', async () => {
        const roomName = modalRoomName.value.trim() || `${currentUser.username}'s Room`;
        const selectedVis = document.querySelector('input[name="room-visibility"]:checked').value;
        const isPublic = (selectedVis === 'public');
        const passcode = isPublic ? null : modalRoomPasscode.value.trim();

        if (!isPublic && (!passcode || passcode.length < 2)) {
            alert('Please enter a passcode for this private room');
            return;
        }

        btnConfirmCreate.disabled = true;
        try {
            const headers = { 'Content-Type': 'application/json' };
            if (currentUser.token) {
                headers['Authorization'] = `Bearer ${currentUser.token}`;
            }

            const res = await fetch('/api/v1/rooms', {
                method: 'POST',
                headers: headers,
                body: JSON.stringify({
                    name: roomName,
                    userId: currentUser.userId,
                    username: currentUser.username,
                    initialTrackId: null,
                    isPublic: isPublic,
                    passcode: passcode
                })
            });

            if (res.ok) {
                const data = await res.json();
                currentUser.roomId = data.roomId;
                currentUser.userId = data.userId || currentUser.userId;
                currentUser.role = 'HOST';
                currentUser.isPublic = isPublic;

                modalCreateRoom.classList.add('hidden');
                startSession();
            } else {
                alert('Failed to create room.');
            }
        } catch (e) {
            alert('Error creating room: ' + e.message);
        } finally {
            btnConfirmCreate.disabled = false;
        }
    });

    // --- Private Room Passcode Verification ---
    function promptPasscodeForRoom(roomId) {
        pendingPrivateRoomId = roomId;
        inputPromptPasscode.value = '';
        passcodeErrorMsg.classList.add('hidden');
        modalPasscodePrompt.classList.remove('hidden');
        inputPromptPasscode.focus();
    }

    btnClosePasscodeModal.addEventListener('click', () => modalPasscodePrompt.classList.add('hidden'));
    btnCancelPasscode.addEventListener('click', () => modalPasscodePrompt.classList.add('hidden'));

    btnSubmitPasscode.addEventListener('click', async () => {
        const code = inputPromptPasscode.value.trim();
        if (!code) {
            passcodeErrorMsg.textContent = 'Passcode required.';
            passcodeErrorMsg.classList.remove('hidden');
            return;
        }

        try {
            const res = await fetch(`/api/v1/rooms/${pendingPrivateRoomId}/verify`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ passcode: code })
            });

            if (res.ok) {
                const data = await res.json();
                if (data.valid) {
                    modalPasscodePrompt.classList.add('hidden');
                    initiateJoinRoom(pendingPrivateRoomId);
                } else {
                    passcodeErrorMsg.textContent = 'Incorrect passcode. Access denied.';
                    passcodeErrorMsg.classList.remove('hidden');
                }
            }
        } catch (e) {
            passcodeErrorMsg.textContent = 'Verification error. Try again.';
            passcodeErrorMsg.classList.remove('hidden');
        }
    });

    function initiateJoinRoom(roomId) {
        currentUser.roomId = roomId;
        currentUser.role = 'LISTENER';
        startSession();
    }

    // --- VIEW 3: Session Management & Player Synchronization ---
    btnLeaveRoom.addEventListener('click', () => {
        if (wsClient) {
            wsClient.disconnect();
            wsClient = null;
        }
        if (driftEngine) {
            driftEngine.stopDriftLoop();
            if (driftEngine.audio) {
                driftEngine.audio.pause();
                driftEngine.audio.src = '';
            }
        }
        currentUser.roomId = null;
        switchView('rooms');
        fetchActiveRooms();
    });

    function startSession() {
        switchView('player');

        displayRoomId.textContent = currentUser.roomId;
        displayRoomType.textContent = (currentUser.isPublic === false) ? 'PRIVATE' : 'PUBLIC';
        displayRoomType.className = `type-pill ${currentUser.isPublic === false ? 'private' : 'public'}`;
        updateRoleBadge(currentUser.role);

        // Reset player UI to clean idle state
        trackTitle.textContent = 'No track playing';
        trackArtist.textContent = 'Search or choose a track to begin';
        trackArtwork.src = DEFAULT_VINYL_ARTWORK;
        progressFill.style.width = '0%';
        timeCurrent.textContent = '0:00';
        timeTotal.textContent = '0:00';
        playIcon.classList.remove('hidden');
        pauseIcon.classList.add('hidden');

        // Initialize Synchronization Stack
        clockSync = new ClockSyncManager();
        driftEngine = new AudioDriftEngine(clockSync);
        wsClient = new MuzeWebSocketClient(currentUser.roomId, currentUser.userId, currentUser.username, currentUser.isGuest);

        clockSync.setWebSocketClient(wsClient);

        // Telemetry handlers
        let lastUiSecond = -1;
        let lastTelemetryRender = 0;

        clockSync.onCalibrationUpdate((cal) => {
            hudRtt.textContent = `${cal.rttMs} ms`;
            hudOffset.textContent = `${cal.offsetMs > 0 ? '+' : ''}${cal.offsetMs} ms`;
        });

        driftEngine.onTelemetryUpdate((tel) => {
            const now = performance.now();

            if (!isScrubbing && tel.durationSec > 0) {
                const percent = (tel.currentTimeSec / tel.durationSec) * 100;
                progressFill.style.width = `${Math.min(100, Math.max(0, percent))}%`;
            }

            if (now - lastTelemetryRender > 400) {
                hudDelta.textContent = `${tel.deltaMs > 0 ? '+' : ''}${tel.deltaMs} ms`;
                hudCorrections.textContent = tel.correctionCount;
                updateSyncBadge(tel.syncState, tel.deltaMs);
                lastTelemetryRender = now;
            }

            const currentSecFloor = Math.floor(tel.currentTimeSec);
            if (currentSecFloor !== lastUiSecond) {
                timeCurrent.textContent = formatTime(tel.currentTimeSec);
                timeTotal.textContent = formatTime(tel.durationSec);
                lastUiSecond = currentSecFloor;
            }

            if (tel.autoplayBlocked) {
                autoplayNotice.classList.remove('hidden');
            } else {
                autoplayNotice.classList.add('hidden');
            }
        });

        // WebSocket Event Handlers
        wsClient.on('onOpen', () => {
            clockSync.startPeriodicSync();
        });

        wsClient.on('onClockResponse', (msg) => {
            clockSync.handleSyncResponse(msg);
        });

        wsClient.on('onSyncState', (msg) => {
            driftEngine.applySyncState(msg);

            // Toggle play/pause button state
            if (msg.status === 'PLAYING' && msg.trackUrl) {
                playIcon.classList.add('hidden');
                pauseIcon.classList.remove('hidden');
            } else {
                playIcon.classList.remove('hidden');
                pauseIcon.classList.add('hidden');
            }

            // Update track details & artwork
            if (msg.trackTitle && msg.trackTitle !== 'No track playing') {
                trackTitle.textContent = msg.trackTitle;
                trackArtist.textContent = msg.trackArtist || 'muze Artist';
            } else {
                trackTitle.textContent = 'No track playing';
                trackArtist.textContent = 'Search or choose a track to begin';
            }

            if (msg.trackArtworkUrl) {
                trackArtwork.src = msg.trackArtworkUrl;
            } else {
                trackArtwork.src = DEFAULT_VINYL_ARTWORK;
            }

            if (msg.durationMs && msg.durationMs > 0) {
                driftEngine.expectedDurationSec = msg.durationMs / 1000;
                timeTotal.textContent = formatTime(driftEngine.expectedDurationSec);
            } else {
                driftEngine.expectedDurationSec = 0;
                timeTotal.textContent = '0:00';
                timeCurrent.textContent = '0:00';
                progressFill.style.width = '0%';
            }
        });

        wsClient.on('onParticipants', (roster) => {
            renderParticipants(roster);
            const me = roster.find(p => p.userId === currentUser.userId);
            if (me && me.role !== currentUser.role) {
                currentUser.role = me.role;
                updateRoleBadge(currentUser.role);
            }
        });

        wsClient.on('onQueueUpdated', (queue) => {
            currentQueue = queue || [];
            renderQueue(currentQueue);
        });

        wsClient.on('onRoomClosed', (reason) => {
            alert(reason || 'The host has left the room and the session has ended.');
            btnLeaveRoom.click();
        });

        wsClient.on('onError', (err) => {
            console.warn('Sync notice:', err);
        });

        // Auto-advance song queue when track ends
        if (driftEngine && driftEngine.audio) {
            driftEngine.audio.addEventListener('ended', () => {
                if (isHostOrCoHost() && currentQueue.length > 0) {
                    console.log('Track reached end, advancing song queue...');
                    wsClient.skipTrack();
                }
            });
        }

        wsClient.connect();
    }

    // Play / Pause controls
    btnPlayPause.addEventListener('click', handlePlayPause);
    function handlePlayPause() {
        if (!isHostOrCoHost()) return;
        if (!wsClient || !wsClient.isOpen()) return;

        if (!driftEngine || !driftEngine.currentTrackUrl) {
            alert('No track selected yet! Use the search bar below to find and play a track.');
            musicSearchInput.focus();
            return;
        }

        const isCurrentlyPlaying = (driftEngine && driftEngine.status === 'PLAYING');
        wsClient.send({
            type: isCurrentlyPlaying ? 'COMMAND_PAUSE' : 'COMMAND_PLAY'
        });
    }

    // Skip Track control
    if (btnSkipTrack) {
        btnSkipTrack.addEventListener('click', () => {
            if (!isHostOrCoHost()) return;
            if (!wsClient || !wsClient.isOpen()) return;
            wsClient.skipTrack();
        });
    }

    function isHostOrCoHost() {
        return currentUser && (currentUser.role === 'HOST' || currentUser.role === 'CO_HOST');
    }

    function updateRoleBadge(role) {
        displayUserRole.textContent = role;
        displayUserRole.className = 'role-pill ' + role.toLowerCase();

        const canControl = isHostOrCoHost();
        btnPlayPause.disabled = !canControl;
        if (btnSkipTrack) btnSkipTrack.disabled = !canControl;

        if (driftEngine) {
            driftEngine.setIsHost(canControl);
        }

        if (canControl) {
            progressBar.style.cursor = 'pointer';
            hostHintText.textContent = 'Host: Controls Active';
        } else {
            progressBar.style.cursor = 'not-allowed';
            hostHintText.textContent = 'Listener: Phase-Locked to Host';
        }
        renderQueue(currentQueue);
        if (currentCatalogTracks && currentCatalogTracks.length > 0) {
            renderCatalogTracks(currentCatalogTracks);
        }
    }

    function updateSyncBadge(state, deltaMs) {
        hudStatus.className = 'hud-pill';
        if (state === 'IN_SYNC') {
            hudStatus.textContent = 'IN-SYNC (<25ms)';
            hudStatus.classList.add('pill-green');
        } else if (state === 'SOFT_NUDGE') {
            hudStatus.textContent = `NUDGE (Δ ${deltaMs}ms)`;
            hudStatus.classList.add('pill-yellow');
        } else if (state === 'HARD_SNAP') {
            hudStatus.textContent = 'HARD-SNAP';
            hudStatus.classList.add('pill-red');
        } else if (state === 'STREAM_ERROR') {
            hudStatus.textContent = 'STREAM ERROR';
            hudStatus.classList.add('pill-red');
        } else {
            hudStatus.textContent = state;
            hudStatus.classList.add('pill-gray');
        }
    }

    function renderParticipants(roster) {
        participantsList.innerHTML = '';
        participantCountBadge.textContent = `${roster.length} active`;

        roster.forEach(p => {
            const li = document.createElement('li');
            li.className = 'participant-item';

            const isMe = (p.userId === currentUser.userId);
            li.innerHTML = `
                <div class="p-avatar">${p.username.charAt(0).toUpperCase()}</div>
                <div class="p-info">
                    <span class="p-name">${escapeHtml(p.username)} ${isMe ? '<small>(You)</small>' : ''}</span>
                    <span class="p-role ${p.role.toLowerCase()}">${p.role}</span>
                </div>
            `;
            participantsList.appendChild(li);
        });
    }

    // --- Audio Scrubbing ---
    progressBar.addEventListener('mousedown', (e) => {
        if (!isHostOrCoHost()) return;
        isScrubbing = true;
    });

    window.addEventListener('mouseup', (e) => {
        if (isScrubbing) {
            isScrubbing = false;
            executeSeek(e);
        }
    });

    progressBar.addEventListener('click', (e) => {
        if (!isHostOrCoHost()) return;
        executeSeek(e);
    });

    function executeSeek(e) {
        const rect = progressBar.getBoundingClientRect();
        const clickX = Math.max(0, Math.min(rect.width, e.clientX - rect.left));
        const fraction = clickX / rect.width;
        const totalDuration = (driftEngine && driftEngine.expectedDurationSec) ? driftEngine.expectedDurationSec : 180;
        const targetMs = Math.round(fraction * totalDuration * 1000);

        if (wsClient && wsClient.isOpen()) {
            wsClient.send({
                type: 'COMMAND_SEEK',
                seekPositionMs: targetMs
            });
        }
    }

    volumeSlider.addEventListener('input', (e) => {
        if (driftEngine) driftEngine.setVolume(e.target.value);
    });

    btnCopyLink.addEventListener('click', () => {
        if (!currentUser || !currentUser.roomId) return;
        const url = `${window.location.origin}/?room=${currentUser.roomId}`;
        navigator.clipboard.writeText(url).then(() => {
            const originalText = btnCopyLink.innerHTML;
            btnCopyLink.innerHTML = '<span>✓ Copied!</span>';
            setTimeout(() => { btnCopyLink.innerHTML = originalText; }, 2000);
        });
    });

    btnUnmute.addEventListener('click', () => {
        if (driftEngine && driftEngine.audio) {
            driftEngine.audio.play().then(() => {
                autoplayNotice.classList.add('hidden');
            });
        }
    });

    // --- Music Catalog & Search ---
    const SEARCH_HINT_HTML = '<div class="search-hint-state" style="grid-column: 1 / -1; padding: 24px; text-align: center; color: var(--text-secondary); font-size: 13px;">Type a song title or artist name above to search and stream tracks.</div>';

    musicSearchInput.addEventListener('input', (e) => {
        const query = e.target.value.trim();
        btnClearSearch.classList.toggle('hidden', query.length === 0);

        if (searchDebounceTimer) clearTimeout(searchDebounceTimer);
        searchDebounceTimer = setTimeout(() => {
            if (query.length >= 2) {
                searchAudiusTracks(query);
            } else if (query.length === 0) {
                catalogResults.innerHTML = SEARCH_HINT_HTML;
            }
        }, 320);
    });

    btnClearSearch.addEventListener('click', () => {
        musicSearchInput.value = '';
        btnClearSearch.classList.add('hidden');
        catalogResults.innerHTML = SEARCH_HINT_HTML;
    });

    async function loadLocalCatalog() {
        try {
            const res = await fetch('/api/v1/tracks');
            if (res.ok) {
                localTracks = await res.json();
            }
        } catch (e) {
            console.error('Failed to load local tracks:', e);
        }
    }

    async function searchAudiusTracks(query) {
        catalogResults.innerHTML = `<div class="loading-state">Searching catalog for "${escapeHtml(query)}"...</div>`;
        try {
            const res = await fetch(`/api/v1/audius/search?q=${encodeURIComponent(query)}`);
            if (res.ok) {
                const tracks = await res.json();
                if (tracks.length === 0) {
                    catalogResults.innerHTML = `<div class="empty-state">No streamable tracks found for "${escapeHtml(query)}".</div>`;
                } else {
                    renderCatalogTracks(tracks);
                }
            }
        } catch (e) {
            catalogResults.innerHTML = `<div class="empty-state">Search query failed. Check connection.</div>`;
        }
    }

    let currentCatalogTracks = [];

    // --- Song Queue Rendering ---
    function renderQueue(queue) {
        if (!queueCountBadge || !queueList || !queueEmptyMsg) return;
        const count = queue ? queue.length : 0;
        queueCountBadge.textContent = `${count} queued`;

        if (!queue || queue.length === 0) {
            queueList.innerHTML = '';
            queueList.classList.add('hidden');
            queueEmptyMsg.classList.remove('hidden');
            return;
        }

        queueEmptyMsg.classList.add('hidden');
        queueList.classList.remove('hidden');
        queueList.innerHTML = '';

        const canDelete = isHostOrCoHost();

        queue.forEach((item, index) => {
            const li = document.createElement('li');
            li.className = 'queue-item';

            const artUrl = item.artworkUrl || DEFAULT_VINYL_ARTWORK;
            const addedBy = item.addedByUsername || 'Listener';

            li.innerHTML = `
                <span class="queue-index">#${index + 1}</span>
                <img class="queue-thumb" src="${escapeHtml(artUrl)}" alt="Track cover" onerror="this.src='${DEFAULT_VINYL_ARTWORK}'">
                <div class="queue-info">
                    <span class="queue-title">${escapeHtml(item.title || 'Untitled Track')}</span>
                    <span class="queue-sub">${escapeHtml(item.artist || 'Unknown')} • Added by ${escapeHtml(addedBy)}</span>
                </div>
                ${canDelete ? `<button class="btn-remove-queue" title="Remove track from queue" data-queue-id="${escapeHtml(item.queueId)}">✕</button>` : ''}
            `;

            if (canDelete) {
                const btnRemove = li.querySelector('.btn-remove-queue');
                if (btnRemove) {
                    btnRemove.addEventListener('click', (e) => {
                        e.stopPropagation();
                        if (wsClient && wsClient.isOpen()) {
                            wsClient.removeQueuedTrack(item.queueId);
                        }
                    });
                }
            }

            queueList.appendChild(li);
        });
    }

    function renderCatalogTracks(tracks) {
        currentCatalogTracks = tracks || [];
        catalogResults.innerHTML = '';
        if (!tracks || tracks.length === 0) {
            catalogResults.innerHTML = '<div class="empty-state">No tracks available.</div>';
            return;
        }

        const isHost = isHostOrCoHost();

        tracks.forEach(trk => {
            const card = document.createElement('div');
            card.className = 'track-row-card';

            const artUrl = trk.artworkUrl || DEFAULT_VINYL_ARTWORK;
            const durSec = trk.durationMs ? Math.round(trk.durationMs / 1000) : 180;

            card.innerHTML = `
                <img class="track-thumb" src="${escapeHtml(artUrl)}" alt="Cover" onerror="this.src='${DEFAULT_VINYL_ARTWORK}'">
                <div class="track-meta">
                    <span class="track-meta-title">${escapeHtml(trk.title)}</span>
                    <span class="track-meta-artist">${escapeHtml(trk.artist || 'Unknown')}</span>
                </div>
                <span class="track-meta-duration">${formatTime(durSec)}</span>
                <div class="track-actions" style="display: flex; align-items: center; gap: 8px;">
                    <button class="btn-add-queue" title="Add song to queue">+ Add</button>
                    ${isHost ? '<button class="btn-track-play" title="Play immediately">▶ Play</button>' : ''}
                </div>
            `;

            const btnAdd = card.querySelector('.btn-add-queue');
            btnAdd.addEventListener('click', (e) => {
                e.stopPropagation();
                if (!wsClient || !wsClient.isOpen()) {
                    alert('Not connected to room server.');
                    return;
                }
                const streamUrl = trk.streamUrl || trk.effectiveStreamUrl || `/api/v1/tracks/${trk.id}/stream`;
                wsClient.enqueueTrack(
                    trk.id,
                    trk.title,
                    trk.artist,
                    trk.artworkUrl,
                    streamUrl,
                    trk.durationMs || 180000
                );
                const prevHtml = btnAdd.innerHTML;
                btnAdd.innerHTML = '✓ Added!';
                btnAdd.disabled = true;
                setTimeout(() => {
                    btnAdd.innerHTML = prevHtml;
                    btnAdd.disabled = false;
                }, 1500);
            });

            if (isHost) {
                const btnPlay = card.querySelector('.btn-track-play');
                if (btnPlay) {
                    btnPlay.addEventListener('click', (e) => {
                        e.stopPropagation();
                        handleSelectSong(trk);
                    });
                }
            }

            card.addEventListener('click', () => {
                if (isHostOrCoHost()) {
                    handleSelectSong(trk);
                } else {
                    btnAdd.click();
                }
            });

            catalogResults.appendChild(card);
        });
    }

    function handleSelectSong(trk) {
        if (!isHostOrCoHost()) {
            alert('Only the Host can switch songs in this room.');
            return;
        }
        if (!wsClient || !wsClient.isOpen()) return;

        wsClient.send({
            type: 'COMMAND_SELECT_TRACK',
            trackId: trk.id,
            trackUrl: trk.streamUrl || trk.effectiveStreamUrl || `/api/v1/tracks/${trk.id}/stream`,
            trackTitle: trk.title,
            trackArtist: trk.artist,
            trackArtworkUrl: trk.artworkUrl,
            durationMs: trk.durationMs
        });
    }

    // --- Helpers ---
    function formatTime(seconds) {
        if (!seconds || isNaN(seconds)) return '0:00';
        const m = Math.floor(seconds / 60);
        const s = Math.floor(seconds % 60);
        return `${m}:${s < 10 ? '0' : ''}${s}`;
    }

    function escapeHtml(str) {
        return (str || '').replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    }
});
