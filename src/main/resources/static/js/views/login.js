import { api } from "../api.js";
import { escapeHtml, takeFlash } from "../util.js";

// notice: why they're being asked to log in (e.g. "your session has expired"), or null
export function showLogin(root, onLoggedIn, notice = null) {
	const flashMessage = takeFlash();   // e.g. "Account created…" from the sign-up screen
	root.innerHTML = `
        <form class="login-panel" id="login-form">
            <h1>Log in to TripSense</h1>
            ${flashMessage ? `<p class="ok" role="status">${escapeHtml(flashMessage)}</p>` : ""}
            ${notice && !flashMessage ? `<p class="notice" role="status">${escapeHtml(notice)}</p>` : ""}
            <label for="email">Email</label>
            <input id="email" name="email" type="email" autocomplete="email" required>
            
            <label for="password">Password</label>
            <input id="password" name="password" type="password" autocomplete="current-password" required>
            
            <p class="err" id="error-message" role="alert"></p>
            <button type="submit">Log in</button>
            <p class="switch-auth">New here? <a href="#/register">Create an account</a></p>
        </form>
    `;

	const form = root.querySelector("#login-form");
	const errorMessage = root.querySelector("#error-message");

	form.onsubmit = async (event) => {
		event.preventDefault();

		// Access inputs directly by their 'name' attribute via form.elements
		const email = form.elements.email.value.trim();
		const password = form.elements.password.value;

		try {
			await api.login(email, password);
			onLoggedIn();
		} catch (error) {
			errorMessage.textContent =
				error.status === 401 ? "Invalid email or password." : error.message;
		}
	};
}