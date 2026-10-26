import axios from "axios";
import { expireMyHrAuthentication, getAccessToken } from "../auth/myHrSession";

export const getApiBaseUrl = () => {
    if (typeof window !== "undefined") {
        // A live Capacitor app is served by Vite over the LAN. Always use its
        // same-origin proxy before considering a local build-time API setting.
        if (window.location.port === "5173") {
            localStorage.removeItem("waypoint_api_url");
            return "/api";
        }

        let customUrl = localStorage.getItem("waypoint_api_url");
        if (customUrl) {
            // Automatically correct accidental https:// for local dev LAN endpoints (port 8081, 192.168.x, 10.x, localhost)
            if (customUrl.startsWith("https://") && (
                customUrl.includes(":8081") ||
                customUrl.includes(":5173") ||
                customUrl.includes("192.168.") ||
                customUrl.includes("10.") ||
                customUrl.includes("172.") ||
                customUrl.includes("localhost") ||
                customUrl.includes("127.0.0.1")
            )) {
                customUrl = customUrl.replace("https://", "http://");
                localStorage.setItem("waypoint_api_url", customUrl);
            }
            return customUrl;
        }
    }

    if (import.meta.env.VITE_API_BASE_URL) {
        return import.meta.env.VITE_API_BASE_URL;
    }

    if (typeof window !== "undefined") {
        const isNative = Boolean(
            window.Capacitor?.isNativePlatform?.() ||
            window.location.protocol === "capacitor:" ||
            window.location.protocol === "ionic:" ||
            (window.location.hostname === "localhost" && !window.location.port)
        );

        if (isNative) {
            return import.meta.env.VITE_MOBILE_API_BASE_URL || import.meta.env.VITE_API_BASE_URL || "/api";
        }
    }

    return "/api";
};

const apiClient = axios.create({
    baseURL: getApiBaseUrl(),
    headers: {
        "Content-Type": "application/json",
    },
});

apiClient.interceptors.request.use(
    (config) => {
        if (!config.baseURL || config.baseURL === "/api") {
            config.baseURL = getApiBaseUrl();
        }
        const token = getAccessToken();

        if (token) {
            config.headers.Authorization = `Bearer ${token}`;
        }

        if (typeof FormData !== "undefined" && config.data instanceof FormData) {
            delete config.headers["Content-Type"];
        }

        return config;
    },
    (error) => Promise.reject(error)
);

apiClient.interceptors.response.use(
    (response) => response,
    (error) => {
        const requestUrl = error?.config?.url || "";
        const isAuthenticatedMyHrRequest = requestUrl.startsWith("/myhr/")
            || requestUrl.startsWith("/waypoint-admin/")
            || requestUrl === "/auth/change-password"
            || requestUrl === "/auth/change-email";
        if (error?.response?.status === 401 && isAuthenticatedMyHrRequest) {
            expireMyHrAuthentication();
        }
        return Promise.reject(error);
    }
);

export default apiClient;
