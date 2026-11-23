import type { NextConfig } from 'next';
const apiServer = process.env.API_INTERNAL_URL;

const nextConfig: NextConfig = {
  output: 'standalone',
  async rewrites() {
    return apiServer ? [{
      source: '/api/v1/:path*',
      destination: `${apiServer.replace(/\/$/, '')}/api/v1/:path*`,
    }] : [];
  },
};
export default nextConfig;
