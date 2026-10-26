/**
 * WaypointAudioEngine - Procedural Web Audio API sound synthesizer
 * Provides subtle, tactile, futuristic audio cues without external audio files.
 */
class WaypointAudioEngine {
  constructor() {
    this.ctx = null;
    this.enabled = false;
    this.gainNode = null;
  }

  init() {
    if (this.ctx) return;
    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (AudioCtx) {
        this.ctx = new AudioCtx();
        this.gainNode = this.ctx.createGain();
        this.gainNode.gain.value = 0.15; // Safe default volume
        this.gainNode.connect(this.ctx.destination);
      }
    } catch {
      // AudioContext unavailable or blocked by policy
    }
  }

  toggleSound() {
    this.init();
    if (this.ctx && this.ctx.state === 'suspended') {
      this.ctx.resume();
    }
    this.enabled = !this.enabled;
    if (this.enabled) {
      this.playChord([523.25, 659.25, 783.99], 0.12); // C5 major chime
    }
    return this.enabled;
  }

  playClick() {
    if (!this.enabled || !this.ctx) return;
    try {
      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(800, this.ctx.currentTime);
      osc.frequency.exponentialRampToValueAtTime(300, this.ctx.currentTime + 0.04);
      gain.gain.setValueAtTime(0.08, this.ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, this.ctx.currentTime + 0.04);
      osc.connect(gain);
      gain.connect(this.gainNode);
      osc.start();
      osc.stop(this.ctx.currentTime + 0.04);
    } catch {
      // Silent fail
    }
  }

  playSlider(factor = 0.5) {
    if (!this.enabled || !this.ctx) return;
    try {
      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();
      osc.type = 'triangle';
      const freq = 220 + factor * 440;
      osc.frequency.setValueAtTime(freq, this.ctx.currentTime);
      gain.gain.setValueAtTime(0.05, this.ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, this.ctx.currentTime + 0.05);
      osc.connect(gain);
      gain.connect(this.gainNode);
      osc.start();
      osc.stop(this.ctx.currentTime + 0.05);
    } catch {
      // Silent fail
    }
  }

  playChord(notes = [440, 554.37, 659.25], duration = 0.25) {
    if (!this.enabled || !this.ctx) return;
    try {
      const now = this.ctx.currentTime;
      notes.forEach((freq, idx) => {
        const osc = this.ctx.createOscillator();
        const gain = this.ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(freq, now + idx * 0.02);
        gain.gain.setValueAtTime(0.04, now + idx * 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, now + duration);
        osc.connect(gain);
        gain.connect(this.gainNode);
        osc.start(now + idx * 0.02);
        osc.stop(now + duration);
      });
    } catch {
      // Silent fail
    }
  }

  playLaser() {
    if (!this.enabled || !this.ctx) return;
    try {
      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();
      osc.type = 'sawtooth';
      osc.frequency.setValueAtTime(1400, this.ctx.currentTime);
      osc.frequency.exponentialRampToValueAtTime(200, this.ctx.currentTime + 0.2);
      gain.gain.setValueAtTime(0.06, this.ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, this.ctx.currentTime + 0.2);
      osc.connect(gain);
      gain.connect(this.gainNode);
      osc.start();
      osc.stop(this.ctx.currentTime + 0.2);
    } catch {
      // Silent fail
    }
  }

  playSuccess() {
    if (!this.enabled || !this.ctx) return;
    try {
      const now = this.ctx.currentTime;
      const notes = [523.25, 659.25, 783.99, 1046.5]; // C5, E5, G5, C6
      notes.forEach((freq, idx) => {
        const osc = this.ctx.createOscillator();
        const gain = this.ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(freq, now + idx * 0.06);
        gain.gain.setValueAtTime(0.08, now + idx * 0.06);
        gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.45);
        osc.connect(gain);
        gain.connect(this.gainNode);
        osc.start(now + idx * 0.06);
        osc.stop(now + 0.45);
      });
    } catch {
      // Silent fail
    }
  }
}

export const audioEngine = new WaypointAudioEngine();
