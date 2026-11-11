'use client';

import { useEffect, useState } from 'react';

export default function GooglePhotosCallback() {
  const [status, setStatus] = useState('Zpracovávám přihlášení k Google Photos...');

  useEffect(() => {
    const hash = window.location.hash;
    
    if (!hash) {
      setStatus('❌ Chyba: Google nevrátil přístupový token. Zkus to znovu.');
      return;
    }

    const params = new URLSearchParams(hash.substring(1));
    const access_token = params.get('access_token');
    const error = params.get('error');

    if (error) {
      setStatus(`❌ Google zamítl přístup: ${error}`);
      return;
    }

    if (access_token) {
      // Store token with expiry for later use
      const expires_in = parseInt(params.get('expires_in') || '3600');
      const expires_at = Date.now() + expires_in * 1000;
      localStorage.setItem('diarium_google_token', access_token);
      localStorage.setItem('diarium_google_token_expires_at', String(expires_at));
      setStatus('✅ Připojeno k Google Photos! Přesměrovávám...');
      
      // Redirect back to the main page
      setTimeout(() => {
        window.location.href = '/';
      }, 500);
      return;
    }

    setStatus('❌ V odpovědi chybí access_token.');
  }, []);

  return (
    <div style={{
      display: 'flex',
      flexDirection: 'column',
      justifyContent: 'center',
      alignItems: 'center',
      minHeight: '100vh',
      background: '#0f0f23',
      color: '#a5b4fc',
      fontFamily: 'monospace',
      padding: '2rem',
      textAlign: 'center',
    }}>
      <h1 style={{ fontSize: '1.5rem', marginBottom: '1rem' }}>Google Photos</h1>
      <p style={{ fontSize: '1rem' }}>{status}</p>
    </div>
  );
}
