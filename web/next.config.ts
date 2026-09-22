import type { NextConfig } from 'next';
const apiServer = process.env.API_INTERNAL_URL ?? 'http://localhost:8000';

const nextConfig: NextConfig = {
  output: 'standalone',
  async rewrites() {
    return [
      {
        source: '/api/v1/:path*',
        destination: `${apiServer}/api/v1/:path*`,
      },
    ];
  },
};
export default nextConfig;
