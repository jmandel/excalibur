import React, { useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Check, Download, ExternalLink, RotateCcw, Smartphone, Sword, Upload } from 'lucide-react';
import clsx from 'clsx';
import { useStore } from './store';
import { devices } from './devices';
import { STAGES, stageIndex, type Stage } from './stages';
import './styles.css';

function StageRail({ stage, fraction }: { stage: Stage; fraction: number }) {
  const idx = stageIndex(stage);
  return (
    <div className="rail">
      <div className="railTrack">
        <div className="railFill" style={{ width: `${Math.round(fraction * 100)}%` }} />
        <div className="railDots">
          {STAGES.map((s, i) => <span key={s.id} className={clsx('dot', i <= idx && 'done')} />)}
        </div>
      </div>
      <div className="railLabels">
        {STAGES.map((s, i) => <span key={s.id} className={clsx(i <= idx && 'on')}>{s.label}</span>)}
      </div>
    </div>
  );
}

function DevicePicker() {
  const device = useStore((s) => s.device);
  const setDevice = useStore((s) => s.setDevice);
  return (
    <label className="devicePicker">
      <span>Format for</span>
      <div className="selectWrap">
        <select value={device} onChange={(e) => setDevice(e.target.value)}>
          {devices.map((d) => <option key={d.id} value={d.id}>{d.label}</option>)}
        </select>
      </div>
    </label>
  );
}

function Dropzone() {
  const convertFile = useStore((s) => s.convertFile);
  const convertSample = useStore((s) => s.convertSample);
  const inputRef = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);

  return (
    <div className="stageCard">
      <div
        className={clsx('dropzone', over && 'over')}
        role="button"
        tabIndex={0}
        onClick={() => inputRef.current?.click()}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') inputRef.current?.click(); }}
        onDragOver={(e) => { e.preventDefault(); setOver(true); }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => { e.preventDefault(); setOver(false); const f = e.dataTransfer.files?.[0]; if (f) convertFile(f); }}
      >
        <Upload className="dropIcon" />
        <div className="dropTitle">Drop a book here</div>
        <div className="dropOr">or <span className="link">choose a file</span></div>
        <div className="dropFormats">EPUB · MOBI · FB2</div>
        <input
          ref={inputRef}
          hidden
          type="file"
          accept=".epub,.mobi,.fb2,.prc,.txt,.html,.htmlz"
          onChange={(e) => { const f = e.target.files?.[0]; if (f) convertFile(f); e.target.value = ''; }}
        />
      </div>
      <div className="dzFooter">
        <DevicePicker />
        <button className="sampleLink" onClick={() => convertSample(2)}>
          In a hurry? Try <em>The Hunting of the Snark</em>.
        </button>
      </div>
    </div>
  );
}

function ConvertingCard() {
  const { book, stage, fraction, label, lastLine } = useStore();
  if (!book) return null;
  return (
    <div className="stageCard bookCard">
      <h2 className="bookTitle">{book.title}</h2>
      <div className="bookMeta">{[book.author, book.ext.toUpperCase(), book.sizeText].filter(Boolean).join(' · ')}</div>
      <StageRail stage={stage} fraction={fraction} />
      <div className="convLabel">{label}</div>
      {lastLine && lastLine !== label && <div className="convLine">{lastLine}</div>}
    </div>
  );
}

function DoneCard() {
  const { book, resultUrl, resultName, reset } = useStore();
  if (!book) return null;
  return (
    <div className="stageCard bookCard done">
      <div className="readyMark"><Check /></div>
      <h2 className="bookTitle">{book.title}</h2>
      <div className="bookMeta">Ready for your Kindle · AZW3</div>
      <div className="doneActions">
        <a className="downloadBtn" href={resultUrl} download={resultName}>
          <Download /> Download {resultName}
        </a>
        <button className="textBtn" onClick={reset}><RotateCcw /> Convert another</button>
      </div>
    </div>
  );
}

function ErrorCard() {
  const { error, book, reset } = useStore();
  return (
    <div className="stageCard bookCard error">
      {book && <h2 className="bookTitle">{book.title}</h2>}
      <div className="errorTitle">That didn't work</div>
      <div className="errorBody">{error}</div>
      <button className="textBtn" onClick={reset}><RotateCcw /> Try another book</button>
    </div>
  );
}

function App() {
  const phase = useStore((s) => s.phase);
  return (
    <div className="page">
      <header className="brand"><Sword className="brandMark" /> Excalibur</header>

      <main className="hero">
        <h1>Draw any book into Kindle form.</h1>
        <p className="lede">
          Drop an EPUB or MOBI and Excalibur forges a Kindle-ready AZW3 — converted
          right here in your browser. Nothing is uploaded; the book never leaves your device.
        </p>

        {phase === 'idle' && <Dropzone />}
        {phase === 'converting' && <ConvertingCard />}
        {phase === 'done' && <DoneCard />}
        {phase === 'error' && <ErrorCard />}
      </main>

      <footer className="footer">
        <div className="footerRow">
          <div className="footerText">
            <strong>Reading on a Kindle?</strong>
            <span>Excalibur for Android sends books straight to it over Wi-Fi — no cable, no account.</span>
          </div>
          <a className="apkBtn" href="downloads/excalibur-debug.apk" download>
            <Smartphone /> Get the Android app
          </a>
        </div>
        <p className="verse">“Just the place for a Snark!” the Bellman cried — and converted it.</p>
        <div className="footerLinks">
          <a href="https://github.com/jmandel/excalibur" target="_blank" rel="noopener noreferrer">
            <ExternalLink /> Source on GitHub
          </a>
          <span>Conversion by calibre · free software under the GPLv3</span>
        </div>
      </footer>
    </div>
  );
}

createRoot(document.getElementById('root')!).render(<App />);
